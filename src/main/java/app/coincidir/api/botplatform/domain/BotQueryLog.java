package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * BotQueryLog — registro de cada query SQL libre que el bot ejecutó vía la
 * tool built-in execute_sql contra un BotConnector.
 *
 * Sirve para:
 *   • Debug: ver qué SQL armó el LLM cuando una respuesta sale rara.
 *   • Auditoría: el cliente puede pedir el log de queries de su BD.
 *   • Cost/latency: medir cuánto tarda cada query y de qué tamaño es.
 *   • Detección de abusos: queries cargadas que aporten poco valor.
 *
 * No usamos bot_tool_audit (que es para tools normales con sqlTemplate fijo)
 * porque esta tool tiene shape distinto (no hay toolId — es built-in).
 */
@Getter
@Setter
@Entity
@Table(name = "bot_query_log",
       indexes = {
               @Index(name = "ix_bot_query_log_connector_created", columnList = "connector_id, created_at"),
               @Index(name = "ix_bot_query_log_session", columnList = "session_id")
       })
public class BotQueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    /** sessionId de la conversación que disparó la query (null si fue test manual). */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** Pregunta del usuario que llevó a esta query (resumen, primeros 500 chars). */
    @Column(name = "user_question", length = 500)
    private String userQuestion;

    /** SQL exacto ejecutado (con LIMIT forzado ya aplicado). */
    @Lob
    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    /** "OK", "VALIDATION_FAILED", "TIMEOUT", "SQL_ERROR", "TRUNCATED". */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** Mensaje de error si status != OK. */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** Cuántas filas devolvió la query (puede ser 0). */
    @Column(name = "rows_returned")
    private Integer rowsReturned;

    /** Cuántos ms tardó la ejecución (sin contar validación). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Tamaño del resultado serializado en bytes — para detectar bloating. */
    @Column(name = "result_size_bytes")
    private Integer resultSizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
