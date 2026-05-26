package app.coincidir.api.domain.logging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ClientLogEvent — almacenamiento unificado de errores y warnings que ocurren
 * en la aplicación, tanto del frontend (window.onerror, unhandledrejection,
 * fetch fallidos, React error boundary) como del backend (excepciones del
 * ApiExceptionHandler y eventos WARN/ERROR del Logback appender).
 *
 * Originalmente esta entity solo guardaba errores del frontend (ingest vía
 * /api/client-logs). Con el Error Monitor de /admin (rol DIOS) se amplió para
 * recibir también:
 *
 *  - Backend: cada vez que ApiExceptionHandler captura una excepción
 *    (NotFound, BadRequest, ResponseStatusException, DataIntegrity, genéricas).
 *  - Sistema: ErrorMonitorLogbackAppender persiste eventos WARN+ del logger
 *    raíz del backend (HikariCP timeouts, retries de Anthropic, etc.).
 *
 * Campos:
 *  - source: "frontend" | "backend" | "system" — quién originó el error.
 *  - errorType: clasificación funcional ("DATABASE", "ANTHROPIC_API", "AUTH",
 *    etc.) producida por ErrorClassifier según patrones en el message/stack.
 *  - shortDesc: descripción de una línea para mostrar en la lista.
 *  - detail: stack trace completo o long message; se muestra en el modal.
 *  - recommendation: sugerencia auto-generada por ErrorRecommendationEngine
 *    en base al errorType + patrón del message. NULL si no hay regla matched.
 *  - previousAction: última acción del usuario antes del error. Para el front
 *    sale del breadcrumb tracker. Para el back sale del request URI + método.
 *  - status: "open" | "resolved" | "ignored" — workflow simple para que el
 *    operador de /admin pueda marcar errores como atendidos.
 *  - fingerprint: SHA1 corto de (errorType + shortDesc normalizada + pathname).
 *    Errores con mismo fingerprint se cuentan como "la misma falla repetida"
 *    en las stats. NO unique — guardamos cada ocurrencia individualmente para
 *    no perder timing, breadcrumbs ni context específico de cada caso.
 *  - occurrenceCount: contador *visualizado* al usuario. Se mantiene como 1
 *    por fila; las stats lo calculan con COUNT(*) GROUP BY fingerprint.
 *    Lo dejamos en la entity para futuros caches/denormalizaciones.
 *
 * Compatibilidad con datos viejos: todos los campos nuevos son nullables /
 * tienen default, así que las filas existentes (pre-error-monitor) siguen
 * funcionando — el ErrorMonitorService rellena con defaults en lectura.
 */
@Entity(name = "ClientLogEventLogging")
@Table(name = "client_log_event", indexes = {
        @Index(name = "idx_clep_server_ts",   columnList = "server_ts"),
        @Index(name = "idx_clep_level",       columnList = "level"),
        @Index(name = "idx_clep_source",      columnList = "source"),
        @Index(name = "idx_clep_error_type",  columnList = "error_type"),
        @Index(name = "idx_clep_status",      columnList = "status"),
        @Index(name = "idx_clep_fingerprint", columnList = "fingerprint")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientLogEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_ts", nullable = false)
    private Instant serverTs;

    @Column(name = "client_ts")
    private Instant clientTs;

    /** "error" | "warn" | "fatal" — nivel del log original. */
    @Column(length = 10, nullable = false)
    private String level;

    /**
     * Categoría libre que pasa el caller (frontend manda "ui", "fetch",
     * "react"; backend manda "exception_handler", "logback"). Distinto de
     * errorType (que es clasificación funcional automática).
     */
    @Column(length = 40)
    private String category;

    @Column(length = 80)
    private String app;

    @Column(length = 20)
    private String env;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "request_id", length = 80)
    private String requestId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "user_role", length = 60)
    private String userRole;

    @Column(length = 1024)
    private String url;

    @Column(length = 512)
    private String pathname;

    @Column(name = "user_agent", length = 1024)
    private String userAgent;

    @Column(length = 120)
    private String platform;

    @Column(length = 80)
    private String ip;

    @Column(columnDefinition = "LONGTEXT")
    private String message;

    @Column(name = "data_json", columnDefinition = "LONGTEXT")
    private String dataJson;

    @Column(name = "breadcrumbs_json", columnDefinition = "LONGTEXT")
    private String breadcrumbsJson;

    // ── Campos del Error Monitor (DIOS) ────────────────────────────────────

    /** "frontend" | "backend" | "system". Default "frontend" por compat. */
    @Column(length = 20)
    private String source;

    /**
     * Clasificación funcional auto-detectada. Valores típicos:
     *  DATABASE, ANTHROPIC_API, EMAIL_SMTP, TWILIO, AUTH, VALIDATION,
     *  NOT_FOUND, FRONTEND_FETCH, FRONTEND_RENDER, FRONTEND_UNHANDLED,
     *  INTERNAL.
     * Si no hay match, queda en INTERNAL.
     */
    @Column(name = "error_type", length = 40)
    private String errorType;

    /** Descripción corta (1 línea) — para mostrar en lista. */
    @Column(name = "short_desc", length = 255)
    private String shortDesc;

    /** Descripción detallada — stack o long message. Se muestra en el modal. */
    @Column(name = "detail", columnDefinition = "LONGTEXT")
    private String detail;

    /** Recomendación auto-generada según el tipo y patrón del error. */
    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    /** Acción previa del usuario (último breadcrumb) o request method+URI. */
    @Column(name = "previous_action", length = 500)
    private String previousAction;

    /** "open" | "resolved" | "ignored". Default "open". */
    @Column(length = 20)
    private String status;

    /** Hash para agrupar ocurrencias del MISMO error. */
    @Column(length = 64)
    private String fingerprint;

    /** Contador denormalizado (las stats usan COUNT(*) sobre fingerprint). */
    @Column(name = "occurrence_count")
    private Integer occurrenceCount;

    /**
     * HTTP status devuelto al cliente (solo para errores de backend que
     * vinieron del ApiExceptionHandler). NULL para front/system.
     */
    @Column(name = "http_status")
    private Integer httpStatus;

    /**
     * Nombre completo de la excepción Java (solo backend). Ej:
     * "org.springframework.dao.DataIntegrityViolationException".
     */
    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    /** Usuario que resolvió este error (si status=resolved). */
    @Column(name = "resolved_by", length = 80)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Nota opcional del resolutor. */
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @PrePersist
    public void prePersist() {
        if (serverTs == null) {
            serverTs = Instant.now();
        }
        if (source == null) source = "frontend";
        if (status == null) status = "open";
        if (occurrenceCount == null) occurrenceCount = 1;
    }
}
