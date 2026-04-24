package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ApiCallLog — registro de cada invocación del bot a una API externa.
 *
 * Se escribe de forma "fire and forget" desde ApiCallExecutor: si falla el log
 * no se rompe la ejecución. Sirve para auditoría, debug y detección de abusos.
 *
 * Retención configurable via un cleanup job. Campos pesados (args, response)
 * se guardan truncados para no hacer explotar la tabla.
 */
@Entity
@Table(name = "api_call_log", indexes = {
    @Index(name = "idx_call_log_integration", columnList = "integration_id"),
    @Index(name = "idx_call_log_called_at", columnList = "called_at"),
    @Index(name = "idx_call_log_tool", columnList = "tool_name"),
})
@Data
public class ApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id")
    private Long integrationId;

    @Column(name = "endpoint_id")
    private Long endpointId;

    /** toolName tal cual se invocó (útil si el endpoint fue borrado después). */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    /** HTTP method del endpoint ejecutado. */
    @Column(name = "method", length = 10)
    private String method;

    /** URL completa resuelta (con params sustituidos). */
    @Column(name = "url", length = 1000)
    private String url;

    /** JSON serializado de los args que recibió. Truncado si supera 2000 chars. */
    @Column(name = "args_json", length = 2000)
    private String argsJson;

    /** HTTP status de la respuesta (o 0 si no hubo). */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** true si la llamada fue exitosa (2xx). */
    @Column(name = "ok", nullable = false)
    private Boolean ok;

    /** Mensaje de error si aplica. */
    @Column(name = "error", length = 500)
    private String error;

    /** Primeros 500 chars de la respuesta (para debug sin guardar todo). */
    @Column(name = "response_excerpt", length = 500)
    private String responseExcerpt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "called_at", nullable = false, updatable = false)
    private Instant calledAt;

    @PrePersist
    void onCreate() {
        if (calledAt == null) calledAt = Instant.now();
    }
}
