package app.coincidir.api.audit.service;

import app.coincidir.api.audit.domain.AuditLog;
import app.coincidir.api.audit.event.AuditEvent;
import app.coincidir.api.audit.repository.AuditLogRepository;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.*;

/**
 * AuditEventListener — procesa AuditEvents asincrónicamente y persiste
 * AuditLog en la BD.
 *
 * Hace 3 cosas:
 *  1. Resuelve el usuario actual desde el SecurityContext (snapshot de
 *     username + role para que el log sobreviva al borrado del user).
 *  2. Calcula el diff entre oldValue y newValue (qué campos cambiaron).
 *  3. Arma un summary legible si el caller no pasó uno explícito.
 *
 * Usa @Async para no afectar el thread del controller. Si falla algo, lo
 * loguea y sigue — un fallo de auditoría NUNCA debe romper la operación
 * principal del usuario.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogRepository repo;
    private final PanelUserRepository userRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Campos que SIEMPRE deben filtrarse del diff por privacidad/seguridad.
     * Si aparecen en oldValue/newValue, los excluimos antes de calcular
     * cambios y antes de persistir.
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
        "passwordHash", "password_hash", "password",
        "token", "jwt", "apiKey", "api_key", "secret",
        "anthropicApiKey", "openaiApiKey", "elevenlabsApiKey"
    );

    @EventListener
    @Async
    public void handle(AuditEvent event) {
        try {
            if (event.isSkip()) return;

            AuditLog log = new AuditLog();
            log.setTs(Instant.now());
            log.setAction(event.getAction());
            log.setEntityType(event.getEntityType());
            log.setEntityId(event.getEntityId());
            log.setEntityLabel(event.getEntityLabel());
            log.setModule(event.getModule());
            log.setSource(event.getSource() != null ? event.getSource() : event.getModule());

            // ── Quién: resolvemos desde SecurityContext ──
            resolveUser(log);

            // ── Contexto: IP y user-agent del request actual si está ──
            resolveRequestContext(log);

            // ── Diff y summary ──
            Map<String, Object[]> diff = computeDiff(event.getOldValue(), event.getNewValue());
            if (!diff.isEmpty()) {
                try {
                    log.setChangesJson(objectMapper.writeValueAsString(diff));
                } catch (JsonProcessingException e) {
                    AuditEventListener.log.warn("[Audit] no se pudo serializar diff: {}", e.getMessage());
                }
            }

            // Summary: si vino explícito, gana. Si no, armamos uno desde
            // el diff o desde la acción.
            if (event.getSummary() != null && !event.getSummary().isBlank()) {
                log.setSummary(truncate(event.getSummary(), 500));
            } else {
                log.setSummary(truncate(buildAutoSummary(event, diff), 500));
            }

            repo.save(log);

        } catch (Exception e) {
            // No romper nada. Solo logueamos al log de Spring para debug.
            log.error("[Audit] error procesando evento {} (entity={}/{}): {}",
                event.getAction(), event.getEntityType(), event.getEntityId(), e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void resolveUser(AuditLog log) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                // Acción del sistema (job, listener interno, request sin auth).
                log.setUsername("system");
                log.setRole("SYSTEM");
                return;
            }
            String username = auth.getName();
            log.setUsername(username);
            // Resolver userId + role real desde la BD (no del JWT) para tener
            // siempre el rol actualizado al momento del log.
            Optional<PanelUser> userOpt = userRepo.findByUsername(username);
            if (userOpt.isPresent()) {
                PanelUser u = userOpt.get();
                log.setUserId(u.getId());
                log.setRole(u.getRole());
            } else {
                // Usuario autenticado por JWT pero ya no existe en BD — raro
                // pero posible si lo borraron en otra pestaña. Snapshot del JWT.
                log.setRole(extractRoleFromAuth(auth));
            }
        } catch (Exception e) {
            // Si todo falla, al menos guardamos algo para no perder el evento.
            log.setUsername("unknown");
            log.setRole("UNKNOWN");
        }
    }

    private String extractRoleFromAuth(Authentication auth) {
        try {
            return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(s -> s != null && s.startsWith("ROLE_"))
                .findFirst()
                .map(s -> s.substring(5))
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void resolveRequestContext(AuditLog log) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest req = attrs.getRequest();
            log.setIpAddress(extractClientIp(req));
            String ua = req.getHeader("User-Agent");
            if (ua != null) log.setUserAgent(truncate(ua, 300));
        } catch (Exception e) {
            // sin request scope, ej: jobs
        }
    }

    private String extractClientIp(HttpServletRequest req) {
        String xfwd = req.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            // Primer IP de la lista (cliente real, no los proxies)
            int comma = xfwd.indexOf(',');
            return (comma > 0 ? xfwd.substring(0, comma) : xfwd).trim();
        }
        return req.getRemoteAddr();
    }

    /**
     * Calcula el diff entre dos mapas. Devuelve un mapa donde cada key es un
     * campo que cambió, y el valor es un array [valorViejo, valorNuevo].
     *
     * - Si oldValue es null: todas las keys de newValue van con [null, val].
     * - Si newValue es null: todas las keys de oldValue van con [val, null].
     * - Si ambos están: solo se incluyen las keys que difieren.
     *
     * Filtra campos sensibles (passwords, tokens) antes de comparar.
     */
    private Map<String, Object[]> computeDiff(Map<String, Object> oldValue,
                                              Map<String, Object> newValue) {
        Map<String, Object[]> diff = new LinkedHashMap<>();
        if (oldValue == null && newValue == null) return diff;

        if (oldValue == null) {
            // Create — todas las keys son nuevas
            for (Map.Entry<String, Object> e : newValue.entrySet()) {
                if (isSensitive(e.getKey())) continue;
                if (e.getValue() == null) continue;
                diff.put(e.getKey(), new Object[] { null, e.getValue() });
            }
            return diff;
        }

        if (newValue == null) {
            // Delete — todas las keys eran
            for (Map.Entry<String, Object> e : oldValue.entrySet()) {
                if (isSensitive(e.getKey())) continue;
                if (e.getValue() == null) continue;
                diff.put(e.getKey(), new Object[] { e.getValue(), null });
            }
            return diff;
        }

        // Update — comparar
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(oldValue.keySet());
        allKeys.addAll(newValue.keySet());
        for (String key : allKeys) {
            if (isSensitive(key)) continue;
            Object oldV = oldValue.get(key);
            Object newV = newValue.get(key);
            if (!Objects.equals(oldV, newV)) {
                diff.put(key, new Object[] { oldV, newV });
            }
        }
        return diff;
    }

    private boolean isSensitive(String key) {
        if (key == null) return false;
        String norm = key.toLowerCase().replaceAll("[_-]", "");
        for (String s : SENSITIVE_FIELDS) {
            if (s.toLowerCase().replaceAll("[_-]", "").equals(norm)) return true;
        }
        return norm.contains("password") || norm.contains("token") || norm.endsWith("apikey");
    }

    /**
     * Si el caller no pasó summary, armamos uno descriptivo desde el diff
     * o la acción. Ejemplos:
     *  - "Creó Reserva"
     *  - "Modificó 2 campos: estado, hora"
     *  - "Eliminó Reserva"
     *  - "reservation.status_change"  (fallback si no hay nada)
     */
    private String buildAutoSummary(AuditEvent event, Map<String, Object[]> diff) {
        String verb = extractVerb(event.getAction());
        String entity = event.getEntityType() != null ? event.getEntityType() : "registro";

        if ("create".equals(verb)) {
            return "Creó " + entity + (event.getEntityLabel() != null ? ": " + event.getEntityLabel() : "");
        }
        if ("delete".equals(verb)) {
            return "Eliminó " + entity + (event.getEntityLabel() != null ? ": " + event.getEntityLabel() : "");
        }
        if (!diff.isEmpty()) {
            String fields = String.join(", ", diff.keySet());
            int n = diff.size();
            return "Modificó " + n + " campo" + (n == 1 ? "" : "s") + ": " + fields;
        }
        // Fallback: usar el action directo
        return event.getAction();
    }

    /** Extrae el verbo de "reservation.update" → "update". */
    private String extractVerb(String action) {
        if (action == null) return "action";
        int dot = action.indexOf('.');
        return dot >= 0 ? action.substring(dot + 1) : action;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
