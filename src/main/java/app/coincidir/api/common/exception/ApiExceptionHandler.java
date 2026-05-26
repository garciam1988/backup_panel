package app.coincidir.api.common.exception;

import app.coincidir.api.errormonitor.service.ErrorMonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler para devolver errores más explícitos (útil en local/dev).
 * Evita el "Internal Server Error" sin detalle que complica debug.
 *
 * Además del comportamiento original (build response con detail), ahora cada
 * excepción capturada se le pasa al ErrorMonitorService para que quede
 * persistida en la tabla client_log_event y aparezca en /admin → Monitor.
 *
 * La captura es asíncrona (el service usa @Async) — no demoramos la respuesta
 * al cliente. Si el monitor está deshabilitado o el bean no está disponible
 * (por @Autowired(required=false)), el handler sigue funcionando como antes.
 *
 * Filtramos algunos casos para no inflar la tabla con ruido inútil:
 *  - 404 esperados de OPTIONS/HEAD (probes de monitoring, prefetch del browser)
 *  - 401 sin token (probes públicos / sondas)
 *
 * El resto va al monitor SIEMPRE, porque "ruido inútil" depende del contexto
 * y preferimos tener más data filtrable que perder eventos genuinos.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Opcional para que tests unitarios del handler no necesiten levantar el
     * service. En producción siempre está presente (el módulo error monitor
     * es @Service y el bean se crea automáticamente).
     */
    @Autowired(required = false)
    private ErrorMonitorService errorMonitorService;

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String msg = ex.getReason();
        if (msg == null || msg.isBlank()) msg = ex.getMessage();
        return build(status, msg, ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Muy común en estos casos: columnas faltantes / FK / unique constraint.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, rootMessage(ex), ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        // Si es un ErrorResponse (Spring 6), respeta status
        if (ex instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
            return build(status, rootMessage(ex), ex);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, rootMessage(ex), ex);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Throwable ex) {
        // Persistir en el monitor — async, no bloqueante. Si falla, ni nos enteramos
        // (el service traga sus propias excepciones).
        try {
            if (errorMonitorService != null && shouldCapture(status, ex)) {
                HttpServletRequest req = currentRequest();
                errorMonitorService.captureBackendError(ex, req, status.value(), message);
            }
        } catch (Exception ignore) {
            // No queremos que un fallo del monitor rompa la respuesta al cliente.
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        // ayuda a debug en local
        body.put("exception", ex.getClass().getName());
        Throwable cause = ex.getCause();
        if (cause != null) {
            body.put("cause", cause.getClass().getName());
            body.put("causeMessage", cause.getMessage());
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Filtro defensivo para evitar capturar ruido:
     *  - 404 de NotFoundException: SIEMPRE capturar (puede ser un bug real)
     *  - 4xx restantes: capturar — son situaciones que el operador quiere ver
     *  - 5xx: capturar siempre, son los más importantes
     *
     * Posible futuro: configurable via property si en producción un 401
     * legítimo (token vencido del usuario) inunda demasiado la tabla.
     */
    private boolean shouldCapture(HttpStatus status, Throwable ex) {
        // Hoy capturamos todo. Si en producción aparece ruido, podemos:
        //   - Excluir 401 cuando viene del JwtFilter (token expirado normal).
        //   - Excluir 404 cuando el path es /favicon.ico o /robots.txt.
        // Por ahora mantenemos amplio para tener visibilidad total.
        return true;
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) msg = ex.getMessage();
        return msg;
    }
}
