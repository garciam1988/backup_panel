package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotToolAudit — Log de ejecuciones de tools.
 *
 * Cada llamada a un tool deja una fila acá con: quién la invocó, con qué
 * parámetros, duración, resultado (ok/error). Útil para debugging, análisis
 * de uso y detectar anomalías.
 */
@Entity
@Table(name = "bot_tool_audit", indexes = {
        @Index(name = "idx_audit_tool", columnList = "tool_id,created_at"),
        @Index(name = "idx_audit_created", columnList = "created_at"),
})
@Getter @Setter
public class BotToolAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tool_id", nullable = false)
    private Long toolId;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    /** Parámetros enviados, como JSON. */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** Usuario que ejecutó (puede ser el admin desde el panel o el bot). */
    @Column(name = "invoked_by", length = 100)
    private String invokedBy;

    /** Duración en milisegundos. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** true si la ejecución completó sin errores. */
    @Column(name = "success", nullable = false)
    private Boolean success = false;

    /** Cantidad de filas devueltas (QUERY) o afectadas (UPDATE). */
    @Column(name = "rows_affected")
    private Integer rowsAffected;

    /** Mensaje de error si success=false. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
