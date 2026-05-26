package app.coincidir.api.errormonitor.service;

import app.coincidir.api.domain.logging.ClientLogEvent;
import app.coincidir.api.repository.ClientLogEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.persistence.criteria.Predicate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * ErrorMonitorService — fachada central del módulo de monitoreo de errores.
 *
 * Responsabilidades:
 *  1. Persistir errores que vienen del backend (ApiExceptionHandler) y del
 *     Logback appender, clasificándolos y enriqueciéndolos con recomendación.
 *  2. Hacer la búsqueda con filtros para el panel /admin (search()).
 *  3. Computar el fingerprint que agrupa errores idénticos.
 *
 * Diseño:
 *  - El método captureBackendError() se llama desde ApiExceptionHandler en
 *    el thread del request — captura el contexto sincrónicamente y persiste.
 *    Es @Async para no demorar la respuesta al cliente.
 *  - El método captureSystemEvent() lo llama el Logback appender — también
 *    @Async para no bloquear el thread que loguea.
 *  - Ambos son tolerantes a fallo: si por alguna razón no podemos persistir
 *    (BD caída, classifier rompe), tragamos la excepción. Un fallo del monitor
 *    de errores NO debe nunca generar un nuevo error.
 *
 * Por qué @Lazy en el classifier/recommendation engine: para evitar ciclos
 * en el wiring si en el futuro alguno de ellos quiere usar el repository.
 * Hoy no, pero es defensivo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorMonitorService {

    private final ClientLogEventRepository repo;
    private final ObjectMapper objectMapper;

    @Lazy @Autowired
    private ErrorClassifier classifier;

    @Lazy @Autowired
    private ErrorRecommendationEngine recommender;

    /** Flag global para deshabilitar la captura en runtime si algo se descontrola. */
    private volatile boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; }

    // ── Captura desde el ApiExceptionHandler ───────────────────────────────

    /**
     * Persiste una excepción atrapada por el handler global. Se llama en el
     * thread del request (que está procesando un 4xx/5xx) — para no demorar
     * la respuesta usamos @Async. La excepción ya fue convertida en HTTP
     * response cuando esta función se ejecuta.
     *
     * Si el caller no quiere persistir (ej: 404 esperado), no llama acá.
     * El handler decide qué errores son audit-worthy.
     */
    @Async
    public void captureBackendError(Throwable ex, HttpServletRequest req,
                                    Integer httpStatus, String resolvedMessage) {
        if (!enabled) return;
        try {
            String message = resolvedMessage != null ? resolvedMessage : ex.getMessage();
            String stack = stackOf(ex);
            String exClass = ex.getClass().getName();

            String type = classifier.classify(message, stack, "exception_handler");
            String shortDesc = classifier.shortDescription(type, message, stack);
            String recommendation = recommender.recommend(type, message, stack, httpStatus);
            String fingerprint = makeFingerprint(type, shortDesc, req != null ? req.getRequestURI() : null);

            // Determinar nivel: 5xx → error/fatal, 4xx → warn, otros → warn.
            String level = "error";
            if (httpStatus != null) {
                if (httpStatus >= 500) level = "error";
                else if (httpStatus >= 400) level = "warn";
            }

            // Acción previa: para el backend la inferimos del método + URI.
            String previousAction = req != null
                ? (req.getMethod() + " " + req.getRequestURI())
                : null;

            ClientLogEvent ev = baseEvent(level, type, shortDesc, message, stack,
                                          recommendation, previousAction, fingerprint);
            ev.setSource("backend");
            ev.setHttpStatus(httpStatus);
            ev.setExceptionClass(exClass);
            ev.setCategory("exception_handler");

            if (req != null) {
                ev.setUrl(safeTrim(req.getRequestURL() != null ? req.getRequestURL().toString() : null, 1024));
                ev.setPathname(safeTrim(req.getRequestURI(), 512));
                ev.setUserAgent(safeTrim(req.getHeader("User-Agent"), 1024));
                ev.setIp(safeTrim(resolveIp(req), 80));
                ev.setRequestId(safeTrim(req.getHeader("X-Request-Id"), 80));
            }

            attachUser(ev);
            repo.save(ev);
        } catch (Exception swallow) {
            log.warn("[ErrorMonitor] no se pudo persistir error backend: {}",
                    swallow.getMessage());
        }
    }

    // ── Captura desde el Logback appender (eventos WARN/ERROR del logger) ──

    /**
     * Persiste un evento de log del propio backend (Hibernate WARN, Hikari
     * WARN, retries de Anthropic, etc.). El appender lo llama @Async.
     *
     * Filtramos lo siguiente para no inundar:
     *  - El propio logger del ErrorMonitor (evitar loop).
     *  - Spring boot startup INFO (level filter ya lo hace).
     *  - Eventos sin message útil.
     */
    @Async
    public void captureSystemEvent(String level, String loggerName, String message,
                                    String stack, String threadName) {
        if (!enabled) return;
        try {
            if (loggerName != null && loggerName.startsWith("app.coincidir.api.errormonitor")) {
                return; // evitar loop
            }
            if (message == null || message.isBlank()) return;
            // Nivel ya filtrado al WARN+ por el appender, pero guardarda igual.
            String lvl = level == null ? "warn" : level.toLowerCase(Locale.ROOT);

            String type = classifier.classify(message, stack, "logback");
            String shortDesc = classifier.shortDescription(type, message, stack);
            String recommendation = recommender.recommend(type, message, stack, null);
            String fingerprint = makeFingerprint(type, shortDesc, loggerName);

            ClientLogEvent ev = baseEvent(lvl, type, shortDesc, message, stack,
                                          recommendation, "logger=" + loggerName, fingerprint);
            ev.setSource("system");
            ev.setCategory("logback");
            ev.setApp("apibot");
            ev.setUserAgent(safeTrim(threadName, 1024));
            // Para eventos del sistema NO hay HTTP request en contexto (puede ser
            // un job o un listener). Intentamos igual leer la req si hay.
            tryAttachRequest(ev);
            attachUser(ev);
            repo.save(ev);
        } catch (Exception swallow) {
            // log.warn aquí podría re-disparar el appender → no logueamos nada
            // si esto rompe. Es un drop silencioso defensivo.
        }
    }

    // ── Búsqueda paginada con filtros (panel /admin) ───────────────────────

    public Page<ClientLogEvent> search(ErrorMonitorFilter f, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(p, s,
                Sort.by(Sort.Direction.DESC, "serverTs").and(Sort.by(Sort.Direction.DESC, "id")));
        return repo.findAll(buildSpec(f), pageable);
    }

    public ClientLogEvent findById(Long id) {
        return repo.findById(id).orElse(null);
    }

    private Specification<ClientLogEvent> buildSpec(ErrorMonitorFilter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.from != null) ps.add(cb.greaterThanOrEqualTo(root.get("serverTs"), f.from));
            if (f.to != null)   ps.add(cb.lessThanOrEqualTo(root.get("serverTs"), f.to));

            if (notBlank(f.level))     ps.add(cb.equal(cb.lower(root.get("level")), f.level.toLowerCase(Locale.ROOT)));
            if (notBlank(f.source))    ps.add(cb.equal(cb.lower(root.get("source")), f.source.toLowerCase(Locale.ROOT)));
            if (notBlank(f.errorType)) ps.add(cb.equal(root.get("errorType"), f.errorType));
            if (notBlank(f.status))    ps.add(cb.equal(root.get("status"), f.status));
            if (notBlank(f.app))       ps.add(cb.equal(cb.lower(root.get("app")), f.app.toLowerCase(Locale.ROOT)));
            if (notBlank(f.requestId)) ps.add(cb.equal(root.get("requestId"), f.requestId));
            if (notBlank(f.fingerprint)) ps.add(cb.equal(root.get("fingerprint"), f.fingerprint));
            if (f.userId != null)      ps.add(cb.equal(root.get("userId"), f.userId));
            if (notBlank(f.userEmail)) ps.add(cb.equal(cb.lower(root.get("userEmail")), f.userEmail.toLowerCase(Locale.ROOT)));

            if (notBlank(f.q)) {
                String like = "%" + f.q.toLowerCase(Locale.ROOT) + "%";
                Predicate any = cb.or(
                    cb.like(cb.lower(root.get("message")), like),
                    cb.like(cb.lower(root.get("shortDesc")), like),
                    cb.like(cb.lower(root.get("detail")), like),
                    cb.like(cb.lower(root.get("url")), like),
                    cb.like(cb.lower(root.get("pathname")), like),
                    cb.like(cb.lower(root.get("requestId")), like),
                    cb.like(cb.lower(root.get("exceptionClass")), like)
                );
                ps.add(any);
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    // ── Status updates ─────────────────────────────────────────────────────

    public int markResolved(Long id, String resolvedBy, String note) {
        return repo.updateStatus(id, "resolved", resolvedBy, Instant.now(), note);
    }

    public int markIgnored(Long id, String resolvedBy, String note) {
        return repo.updateStatus(id, "ignored", resolvedBy, Instant.now(), note);
    }

    public int reopen(Long id) {
        return repo.updateStatus(id, "open", null, null, null);
    }

    public int resolveAllByFingerprint(String fingerprint, String resolvedBy, String note) {
        if (fingerprint == null || fingerprint.isBlank()) return 0;
        return repo.resolveAllByFingerprint(fingerprint, resolvedBy, Instant.now(), note);
    }

    // ── Limpieza ──────────────────────────────────────────────────────────

    public int deleteOlderThan(int days) {
        Instant before = Instant.now().minusSeconds(days * 86400L);
        return repo.deleteOlderThan(before);
    }

    public int deleteResolvedOlderThan(int days) {
        Instant before = Instant.now().minusSeconds(days * 86400L);
        return repo.deleteResolvedOlderThan(before);
    }

    // ── Helpers internos ──────────────────────────────────────────────────

    private ClientLogEvent baseEvent(String level, String type, String shortDesc,
                                      String message, String stack,
                                      String recommendation, String previousAction,
                                      String fingerprint) {
        ClientLogEvent ev = ClientLogEvent.builder()
                .serverTs(Instant.now())
                .level(safeTrim(level, 10))
                .errorType(safeTrim(type, 40))
                .shortDesc(safeTrim(shortDesc, 255))
                .message(safeTrim(message, 32768))     // hard cap defensivo
                .detail(safeTrim(stack, 65535))         // ídem
                .recommendation(safeTrim(recommendation, 4096))
                .previousAction(safeTrim(previousAction, 500))
                .fingerprint(safeTrim(fingerprint, 64))
                .status("open")
                .occurrenceCount(1)
                .build();
        return ev;
    }

    /**
     * Genera un hash corto y estable para agrupar errores que son "el mismo".
     * Usamos SHA1 truncado: 16 chars hex = 64 bits, suficiente para evitar
     * colisiones en una tabla con cientos de miles de filas.
     */
    private String makeFingerprint(String type, String shortDesc, String contextHint) {
        try {
            // Normalizamos shortDesc removiendo números y partes variables para
            // que "Error en línea 4242" y "Error en línea 8888" agrupen igual.
            String normalized = (shortDesc == null ? "" : shortDesc)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\d+", "N")
                    .replaceAll("\\s+", " ")
                    .trim();
            String input = (type == null ? "" : type) + "|" + normalized + "|" +
                           (contextHint == null ? "" : contextHint);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Genera el stack completo como string. Devuelve null si la excepción es
     * null. Acotamos defensivamente a ~60KB para evitar inflar el LONGTEXT.
     */
    private String stackOf(Throwable ex) {
        if (ex == null) return null;
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        if (s.length() > 60000) s = s.substring(0, 60000) + "\n...[truncated]";
        return s;
    }

    private void attachUser(ClientLogEvent ev) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                ev.setUserEmail(safeTrim(auth.getName(), 255));
                // Si en el futuro queremos resolver el userId real con un repo,
                // se inyecta y se setea acá. Por ahora con el username alcanza
                // para correlacionar (es único en panel_user).
            }
        } catch (Exception ignored) {}
    }

    private void tryAttachRequest(ClientLogEvent ev) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest req = attrs.getRequest();
            if (ev.getUrl() == null && req.getRequestURL() != null)
                ev.setUrl(safeTrim(req.getRequestURL().toString(), 1024));
            if (ev.getPathname() == null) ev.setPathname(safeTrim(req.getRequestURI(), 512));
            if (ev.getUserAgent() == null) ev.setUserAgent(safeTrim(req.getHeader("User-Agent"), 1024));
            if (ev.getIp() == null) ev.setIp(safeTrim(resolveIp(req), 80));
            if (ev.getRequestId() == null) ev.setRequestId(safeTrim(req.getHeader("X-Request-Id"), 80));
        } catch (Exception ignored) {}
    }

    private String resolveIp(HttpServletRequest req) {
        String xfwd = req.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            int comma = xfwd.indexOf(',');
            return (comma > 0 ? xfwd.substring(0, comma) : xfwd).trim();
        }
        return req.getRemoteAddr();
    }

    private String safeTrim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ── DTO interno para filtros ──────────────────────────────────────────

    /**
     * Wrapper de los filtros aceptados por search(). Mantener todo en un
     * objeto en vez de 10 parámetros sueltos hace que el controller pueda
     * componer/validar antes de pasarlo.
     */
    public static class ErrorMonitorFilter {
        public String q;
        public String level;
        public String source;
        public String errorType;
        public String status;
        public String app;
        public String requestId;
        public String fingerprint;
        public Long userId;
        public String userEmail;
        public Instant from;
        public Instant to;
    }
}
