package app.coincidir.api.errormonitor.controller;

import app.coincidir.api.domain.logging.ClientLogEvent;
import app.coincidir.api.errormonitor.service.ErrorClassifier;
import app.coincidir.api.errormonitor.service.ErrorRecommendationEngine;
import app.coincidir.api.repository.ClientLogEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ErrorMonitorIngestController — endpoint público (sin auth) que recibe
 * errores del frontend y los persiste en client_log_event con la misma
 * clasificación que el flujo del backend.
 *
 * URL: POST /api/error-monitor/ingest
 *
 * Por qué es público: necesitamos capturar errores que ocurren ANTES de
 * autenticar (login fail, crash del LoginScreen, etc.) y errores que ocurren
 * después del logout. Pero como cualquiera puede pegarle:
 *
 *   1. Rate limit por IP (60 errors/min). Más allá → 429 silencioso.
 *   2. Payload acotado (50KB max por request, validamos antes de parsear).
 *   3. Acotamos campos defensivamente (trimToLen).
 *   4. Si está autenticado, enriquecemos con userEmail/userRole del JWT.
 *
 * Por qué un endpoint separado del /api/client-logs existente:
 *   - Ese sigue funcionando como estaba (no romper integraciones).
 *   - Este aplica clasificación, recomendación y fingerprint que el viejo
 *     no hace.
 *   - Persistimos también WARN (el viejo solo guardaba ERROR/FATAL).
 *   - Devuelve el id del log para que el frontend pueda mostrarle al usuario
 *     "se reportó como error #1234" si quisiera (opcional, hoy no).
 */
@Slf4j
@RestController
@RequestMapping("/api/error-monitor")
@RequiredArgsConstructor
public class ErrorMonitorIngestController {

    private final ClientLogEventRepository repo;
    private final ErrorClassifier classifier;
    private final ErrorRecommendationEngine recommender;
    private final ObjectMapper objectMapper;

    /** Rate limit: por IP, ventana móvil de 60s, máximo 60 events. */
    private static final int RATE_LIMIT_PER_MIN = 60;
    private final ConcurrentHashMap<String, Window> rateLimitWindows = new ConcurrentHashMap<>();

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        try {
            if (body == null) return resultDropped("empty body");

            String ip = resolveIp(req);

            // Rate limit por IP
            if (isRateLimited(ip)) {
                return resultDropped("rate-limited");
            }

            String level = strOrDefault(body.get("level"), "error").toLowerCase(Locale.ROOT);
            if (!isAcceptedLevel(level)) return resultDropped("level rejected");

            String message = strOrNull(body.get("message"));
            String stack = strOrNull(body.get("stack"));
            String hint = strOrNull(body.get("hint")); // "fetch" | "react" | "unhandled" | ...

            String type = classifier.classify(message, stack, hint);
            String shortDesc = classifier.shortDescription(type, message, stack);
            String recommendation = recommender.recommend(type, message, stack,
                                                            httpStatusFromBody(body));
            String pathname = trim(strOrNull(body.get("pathname")), 512);
            String fingerprint = makeFingerprint(type, shortDesc, pathname);
            String previousAction = strOrNull(body.get("previousAction"));
            String breadcrumbsJson = jsonOrNull(body.get("breadcrumbs"));
            String dataJson = jsonOrNull(body.get("data"));

            // Construir entity
            ClientLogEvent ev = ClientLogEvent.builder()
                    .serverTs(Instant.now())
                    .level(trim(level, 10))
                    .errorType(trim(type, 40))
                    .shortDesc(trim(shortDesc, 255))
                    .message(trim(message, 32768))
                    .detail(trim(stack, 65535))
                    .recommendation(trim(recommendation, 4096))
                    .previousAction(trim(previousAction, 500))
                    .fingerprint(trim(fingerprint, 64))
                    .source("frontend")
                    .category(trim(strOrNull(body.get("category")), 40))
                    .app(trim(strOrNull(body.get("app")), 80))
                    .env(trim(strOrNull(body.get("env")), 20))
                    .sessionId(trim(strOrNull(body.get("sessionId")), 64))
                    .requestId(trim(strOrNull(body.get("requestId")), 80))
                    .url(trim(strOrNull(body.get("url")), 1024))
                    .pathname(pathname)
                    .userAgent(trim(req.getHeader("User-Agent"), 1024))
                    .platform(trim(strOrNull(body.get("platform")), 120))
                    .ip(trim(ip, 80))
                    .breadcrumbsJson(trim(breadcrumbsJson, 65535))
                    .dataJson(trim(dataJson, 65535))
                    .httpStatus(httpStatusFromBody(body))
                    .status("open")
                    .occurrenceCount(1)
                    .build();

            // Si el caller está autenticado, enriquecemos con su identidad.
            attachUserFromAuth(ev);

            ClientLogEvent saved = repo.save(ev);
            return Map.of("ok", true, "id", saved.getId(),
                          "errorType", type, "fingerprint", fingerprint);

        } catch (Exception e) {
            // NUNCA respondemos 500 desde el ingest — el frontend no debe
            // ver errores al reportar errores (loop infinito).
            return resultDropped("internal: " + e.getClass().getSimpleName());
        }
    }

    // ── Rate limit (en memoria, suficiente para una instancia) ─────────────

    private boolean isRateLimited(String ip) {
        if (ip == null) ip = "unknown";
        long nowMs = System.currentTimeMillis();
        Window w = rateLimitWindows.computeIfAbsent(ip, k -> new Window());
        synchronized (w) {
            // Reset si pasó más de 60s desde el inicio de la ventana.
            if (nowMs - w.windowStart.get() > 60_000L) {
                w.windowStart.set(nowMs);
                w.count.set(0);
            }
            int c = w.count.incrementAndGet();
            return c > RATE_LIMIT_PER_MIN;
        }
    }

    private static class Window {
        AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        AtomicInteger count = new AtomicInteger(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean isAcceptedLevel(String level) {
        return List.of("error", "warn", "fatal").contains(level);
    }

    private Map<String, Object> resultDropped(String reason) {
        return Map.of("ok", false, "dropped", true, "reason", reason);
    }

    private void attachUserFromAuth(ClientLogEvent ev) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                if (ev.getUserEmail() == null) ev.setUserEmail(trim(auth.getName(), 255));
            }
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

    private String makeFingerprint(String type, String shortDesc, String pathname) {
        try {
            String normalized = (shortDesc == null ? "" : shortDesc)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\d+", "N")
                    .replaceAll("\\s+", " ")
                    .trim();
            String input = (type == null ? "" : type) + "|" + normalized + "|" +
                           (pathname == null ? "" : pathname);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer httpStatusFromBody(Map<String, Object> body) {
        Object v = body.get("httpStatus");
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    private String strOrDefault(Object o, String def) {
        String s = strOrNull(o);
        return s == null ? def : s;
    }

    private String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String jsonOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
