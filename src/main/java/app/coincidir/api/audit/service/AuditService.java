package app.coincidir.api.audit.service;

import app.coincidir.api.audit.event.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * AuditService — fachada para que los controllers publiquen acciones
 * audit-worthy de forma simple. El método log() arma el AuditEvent y lo
 * publica; el AuditEventListener lo procesa asincrónicamente.
 *
 * Uso típico desde un controller:
 *
 *     auditService.logUpdate("reservation.update", "Reservation",
 *         String.valueOf(rec.getId()), "Reserva de " + name + " " + date,
 *         "reserve", oldData, newData);
 *
 * El listener se encarga del diff y del summary genérico. Si el caller
 * pasa un summary explícito (ej: "Cambió estado de PENDIENTE a CONFIRMADA"),
 * ese gana al genérico.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final ApplicationEventPublisher eventPublisher;

    // ── API principal — atajos por tipo de acción ──────────────────────────

    /** Acción de creación. oldValue = null implícito. */
    public void logCreate(String action, String entityType, String entityId,
                          String entityLabel, String module, Map<String, Object> newValue) {
        publish(action, entityType, entityId, entityLabel, module, null, null, newValue);
    }

    /** Acción de actualización con diff entre oldValue y newValue. */
    public void logUpdate(String action, String entityType, String entityId,
                          String entityLabel, String module,
                          Map<String, Object> oldValue, Map<String, Object> newValue) {
        publish(action, entityType, entityId, entityLabel, module, null, oldValue, newValue);
    }

    /** Acción de borrado. newValue = null implícito. */
    public void logDelete(String action, String entityType, String entityId,
                          String entityLabel, String module, Map<String, Object> oldValue) {
        publish(action, entityType, entityId, entityLabel, module, null, oldValue, null);
    }

    /**
     * Acción sin diff (login, restore backup, activar template, etc.).
     * El summary tiene que venir explícito porque no hay forma de armarlo
     * desde un diff vacío.
     */
    public void logAction(String action, String entityType, String entityId,
                          String entityLabel, String module, String summary) {
        publish(action, entityType, entityId, entityLabel, module, summary, null, null);
    }

    /**
     * Acción con summary custom. Para casos donde la frase armada del diff
     * automático no es lo suficientemente descriptiva (ej: cambios de estado
     * de reserva, donde queremos "Cambió estado a CONFIRMADA" en vez del diff).
     */
    public void logActionWithChanges(String action, String entityType, String entityId,
                                     String entityLabel, String module, String summary,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        publish(action, entityType, entityId, entityLabel, module, summary, oldValue, newValue);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void publish(String action, String entityType, String entityId,
                         String entityLabel, String module, String summary,
                         Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            // Si la acción la origina el bot (no hay autenticación), filtramos
            // según el flag de configuración. La forma rápida de detectarlo es
            // ver si hay request HTTP activa o si module="bot" explícito.
            //
            // Por ahora confiamos en que los callers pasen module="bot" cuando
            // corresponde y filtramos eso. Una mejora futura sería detectarlo
            // por el JWT (si no hay Principal, es del bot/sistema).
            if ("bot".equals(module)) {
                // Las acciones del bot ya quedan en conversation_log con todo
                // el contexto — no las duplicamos acá.
                return;
            }

            // IMPORTANTE: capturamos username, IP y user-agent SINCRÓNICAMENTE
            // acá (en el thread del request), porque el listener corre @Async
            // y ahí el SecurityContextHolder/RequestContextHolder ya no tiene
            // datos (son ThreadLocals del thread original). Si no hacemos
            // esto, todos los logs salen con username="system".
            String capturedUsername = captureCurrentUsername();
            String capturedIp = captureCurrentIp();
            String capturedUserAgent = captureCurrentUserAgent();

            AuditEvent event = AuditEvent.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .entityLabel(entityLabel)
                .module(module)
                .summary(summary)
                .oldValue(oldValue)
                .newValue(newValue)
                .source(deduceSource(module))
                .capturedUsername(capturedUsername)
                .capturedIp(capturedIp)
                .capturedUserAgent(capturedUserAgent)
                .build();

            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // NUNCA romper la operación del usuario por un fallo en el audit.
            // Si no podemos loguear, lo registramos en el log de Spring y seguimos.
            log.warn("[Audit] No se pudo publicar evento {} para {} id={}: {}",
                action, entityType, entityId, e.getMessage());
        }
    }

    /** Lee el username del SecurityContext del thread actual (síncrono). */
    private String captureCurrentUsername() {
        try {
            org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            Object principal = auth.getPrincipal();
            if ("anonymousUser".equals(principal)) return null;
            return auth.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lee la IP del request actual (síncrono). */
    private String captureCurrentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xfwd = req.getHeader("X-Forwarded-For");
            if (xfwd != null && !xfwd.isBlank()) {
                int comma = xfwd.indexOf(',');
                return (comma > 0 ? xfwd.substring(0, comma) : xfwd).trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lee el User-Agent del request actual (síncrono). */
    private String captureCurrentUserAgent() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getHeader("User-Agent");
        } catch (Exception e) {
            return null;
        }
    }

    /** Deduce el source del request actual si está disponible. */
    private String deduceSource(String module) {
        if (module != null) {
            // El module ya nos da una pista buena
            if ("admin".equals(module) || "reserve".equals(module) || "system".equals(module)) {
                return module;
            }
        }
        return getCurrentRequestPath().startsWith("/api/admin/") ? "admin" :
               getCurrentRequestPath().startsWith("/api/panel/") ? "panel" :
               "system";
    }

    private String getCurrentRequestPath() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "";
            HttpServletRequest req = attrs.getRequest();
            return req.getRequestURI();
        } catch (Exception e) {
            return "";
        }
    }
}
