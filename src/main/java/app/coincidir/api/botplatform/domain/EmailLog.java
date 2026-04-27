package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * EmailLog — registro de cada email enviado (o intento fallido).
 * Para auditoría y para detectar spam/abuso. También es la base del
 * rate limiter (consulta cuántos emails se mandaron en el último
 * minuto/día para frenar excesos).
 */
@Entity
@Table(name = "email_log", indexes = {
    @Index(name = "idx_email_log_table", columnList = "table_id"),
    @Index(name = "idx_email_log_recipient", columnList = "recipient"),
    @Index(name = "idx_email_log_sent_at", columnList = "sent_at"),
})
@Data
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id")
    private Long tableId;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "template_id")
    private Long templateId;

    /** Evento que disparó el envío: created | updated | cancelled | reminder | manual. */
    @Column(name = "event", length = 20)
    private String event;

    @Column(name = "recipient", length = 200)
    private String recipient;

    @Column(name = "subject", length = 300)
    private String subject;

    /** true si JavaMail confirmó envío. false si hubo error. */
    @Column(name = "ok", nullable = false)
    private Boolean ok;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }
}
