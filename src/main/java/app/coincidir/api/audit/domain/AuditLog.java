package app.coincidir.api.audit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * AuditLog — registro de una acción realizada por un usuario sobre una
 * entidad del sistema.
 *
 * Se persiste UN log por cada acción audit-worthy: editar reserva, cambiar
 * estado, crear usuario, modificar prompt, etc. Las acciones disparadas
 * por el bot NO se guardan acá (quedan en conversation_log con todo el
 * contexto).
 *
 * Diseño:
 *  - Snapshots de username/role para que el log sobreviva al borrado del user.
 *  - changes_json en formato {"field": [oldValue, newValue]} — solo lo que
 *    cambió, no el documento entero. Para creates: {"field": [null, newValue]}.
 *    Para deletes: {"field": [oldValue, null]}.
 *  - summary es una frase pre-armada legible para listar sin parsear JSON.
 *  - entity_label snapshot del nombre amigable de la entidad al momento.
 *
 * Retención: el job AuditLogRetentionJob borra logs viejos según política
 * (90 días default; algunas acciones críticas se conservan 1 año).
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_ts",     columnList = "ts"),
        @Index(name = "idx_audit_user",   columnList = "user_id"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id")
})
@Getter @Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Momento exacto de la acción. */
    @Column(name = "ts", nullable = false)
    private Instant ts;

    // ── Quién ─────────────────────────────────────────────────────────────

    /** FK a panel_user. Puede ser null si la acción fue del sistema (jobs). */
    @Column(name = "user_id")
    private Long userId;

    /** Snapshot del username al momento de la acción (sobrevive al borrado). */
    @Column(name = "username", length = 80)
    private String username;

    /** Snapshot del rol al momento. */
    @Column(name = "role", length = 60)
    private String role;

    // ── Qué ──────────────────────────────────────────────────────────────

    /**
     * Identificador de la acción en formato "entidad.verbo".
     * Ej: "reservation.create", "reservation.update", "reservation.status_change",
     *     "user.create", "prompt.activate", "config.update".
     * Convención: minúsculas, con punto como separador.
     */
    @Column(name = "action", length = 60, nullable = false)
    private String action;

    /** Tipo de entidad afectada. Ej: "Reservation", "User", "Prompt", "BotTable". */
    @Column(name = "entity_type", length = 40)
    private String entityType;

    /** ID de la entidad. String por flexibilidad (a veces es un slug). */
    @Column(name = "entity_id", length = 80)
    private String entityId;

    /**
     * Etiqueta legible de la entidad al momento. Snapshot — si después la
     * entidad se borra o cambia de nombre, el log sigue mostrando lo que
     * había en ese momento.
     * Ej: "Reserva de Juan Pérez 17/5 21:00", "Prompt v6 Brasas".
     */
    @Column(name = "entity_label", length = 200)
    private String entityLabel;

    // ── Detalle ──────────────────────────────────────────────────────────

    /** Módulo de origen. "admin" | "reserve" | "bot" | "system". */
    @Column(name = "module", length = 40)
    private String module;

    /** Frase legible armada al momento del evento. */
    @Column(name = "summary", length = 500)
    private String summary;

    /**
     * Diff de cambios en JSON, formato:
     *   {"campo1": [valorViejo, valorNuevo], "campo2": [...], ...}
     * Para creates, el primer valor es null.
     * Para deletes, el segundo valor es null.
     * Puede ser null si la acción no tiene diff (ej: login).
     */
    @Column(name = "changes_json", columnDefinition = "LONGTEXT")
    private String changesJson;

    // ── Contexto ─────────────────────────────────────────────────────────

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    /** "panel" | "admin" | "bot" | "system" */
    @Column(name = "source", length = 20)
    private String source;

    @PrePersist
    void onCreate() {
        if (ts == null) ts = Instant.now();
    }
}
