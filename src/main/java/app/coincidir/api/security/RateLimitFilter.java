package app.coincidir.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Filtro global de rate limit. Se aplica a todos los endpoints públicos del
 * bot y bloquea con 429 cuando se excede algún límite.
 *
 * Va ANTES del JWT auth filter — un atacante que ya excedió el límite no
 * gasta CPU pasando por la validación de token.
 *
 * Endpoints cubiertos:
 *   - /api/public/**       (todos los públicos del bot)
 *   - /api/coinbot/**      (chat AI, TTS, etc.)
 *
 * Endpoints EXENTOS (polling normal del frontend, no se cuentan):
 *   - /api/coinbot/health  (health check del bot)
 *   - /api/public/health   (idem)
 *
 * El sessionId-level rate limit (por sesión del chat AI) NO se aplica acá
 * — vive en BotAiController.chat() porque necesita parsear el body JSON
 * para extraer el sessionId, lo cual este filtro no puede hacer sin
 * romper el stream del request.
 *
 * El write-level rate limit (escrituras a tablas) tampoco va acá —
 * vive en PublicBotTableToolsController.execute() porque solo allí
 * sabemos si la operación es write o read según el toolName.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Endpoints exentos del rate limit. Son los que el frontend hace polling
     * automático cada N segundos — si los contáramos, una conversación larga
     * llegaría al límite solo por el polling, sin que el usuario haga nada.
     *
     * Match EXACTO de path (sin query string). Si necesitás un prefijo,
     * agregalo a EXEMPT_PREFIXES.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/coinbot/health",
            "/api/public/health",
            "/api/public/check-command"   // polling del operador → comando al bot
    );

    /**
     * Prefijos exentos. Cubre endpoints con query string variable, como
     * `/api/public/proactive-messages?sessionId=...`.
     */
    private static final String[] EXEMPT_PREFIXES = {
            "/api/public/proactive-messages",  // polling de mensajes proactivos
            "/api/public/check-command"        // por si viene con query strings
    };

    /** Prefijos que sí están sujetos a rate limit. */
    private static final String[] WATCHED_PREFIXES = {
            "/api/public/",
            "/api/coinbot/"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (EXEMPT_PATHS.contains(path)) return true;
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        for (String prefix : WATCHED_PREFIXES) {
            if (path.startsWith(prefix)) return false;
        }
        // Cualquier otro endpoint (panel admin con JWT, etc.) no se filtra
        // acá — esos están protegidos por la auth.
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String ip = extractClientIp(request);
        RateLimitService.Decision d = rateLimitService.checkGeneral(ip);
        if (!d.allowed) {
            writeRateLimitError(response, d, request.getRequestURI());
            log.warn("[RateLimit] BLOQUEADO ip={} path={} reason={} retryAfter={}s",
                    ip, request.getRequestURI(), d.reason, d.retryAfterSeconds);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Extrae la IP del cliente. Railway envía la IP real en X-Forwarded-For;
     * la cabecera puede tener múltiples IPs encadenadas (proxy chains) — usamos
     * la PRIMERA que es la del cliente original.
     *
     * Fallback al RemoteAddr de la conexión TCP si no hay XFF — útil en local.
     *
     * IMPORTANTE: si confiásemos en cualquier IP de XFF, un atacante podría
     * spoofearla mandando su propio header. Por eso tomamos solo la primera
     * y asumimos que Railway sanitiza el resto. En despliegues fuera de
     * Railway (otro proxy), revisar esto.
     */
    private String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /**
     * Escribe respuesta 429 con JSON estructurado y header Retry-After.
     * El frontend bien implementado puede leer Retry-After para hacer
     * backoff exponencial.
     */
    private void writeRateLimitError(HttpServletResponse resp,
                                     RateLimitService.Decision d,
                                     String path) throws IOException {
        resp.setStatus(429);
        resp.setContentType("application/json;charset=utf-8");
        resp.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds));
        // CORS — sin este header, el frontend ve solo "Failed to fetch" sin
        // poder leer el body. Como el filtro es muy temprano, el chain de
        // CORS de Spring todavía no corrió, así que lo agregamos a mano.
        String origin = "*"; // permisivo en respuesta de error — no expone datos
        resp.setHeader("Access-Control-Allow-Origin", origin);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "error", "rate_limit_exceeded",
                "reason", d.reason != null ? d.reason : "rate_exceeded",
                "retryAfter", d.retryAfterSeconds,
                "path", path
        ));
        resp.getWriter().write(body);
        resp.getWriter().flush();
    }
}
