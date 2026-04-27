package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * EmailTemplate — plantilla de email asociada a una BotTable + un evento.
 *
 * Cada tabla puede tener hasta 4 templates: created, updated, cancelled, reminder.
 * "reminder" se materializa con un job programado (Turno 3) — los otros se
 * disparan automáticamente cuando el bot/admin crea/modifica/borra registros.
 *
 * Los templates soportan placeholders {{nombre_columna}} que se reemplazan
 * con el valor del registro al momento del envío. Variables especiales:
 *   - {{_id}}        → ID del registro
 *   - {{_botName}}   → Nombre del bot (config global)
 *   - {{_date}}      → Fecha actual al momento del envío
 */
@Entity
@Table(name = "email_template",
       uniqueConstraints = @UniqueConstraint(columnNames = {"table_id", "event"}))
@Data
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    /** Evento que dispara el envío: "created" | "updated" | "cancelled" | "reminder". */
    @Column(name = "event", length = 20, nullable = false)
    private String event;

    /** Si está apagado, este template no envía mails aunque el evento ocurra. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Subject del mail con placeholders. Ej: "Confirmación de tu reserva — {{nombre}}". */
    @Column(name = "subject", length = 300, nullable = false)
    private String subject;

    /** Body HTML del mail con placeholders. */
    @Column(name = "body_html", columnDefinition = "TEXT", nullable = false)
    private String bodyHtml;

    /**
     * Reply-To opcional. Si está seteado, el mail va con
     * From: "{display} <info@yes-traveluy.com>" + Reply-To: replyTo
     * para que las respuestas le lleguen al cliente real.
     */
    @Column(name = "reply_to", length = 200)
    private String replyTo;

    /** Display name custom para el From. Ej: "Brasas Argentinas". */
    @Column(name = "from_display_name", length = 120)
    private String fromDisplayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
