package app.coincidir.api.common.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler para devolver errores más explícitos (útil en local/dev).
 * Evita el "Internal Server Error" sin detalle que complica debug.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

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

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Throwable ex) {
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
