package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * EmailReminderSent — marca de "ya enviamos el recordatorio para este registro".
 *
 * Cuando el ReminderJob detecta que hay que mandar un recordatorio para
 * un registro, antes de mandarlo verifica que no exista una entrada acá.
 * Si la entrada existe, lo skipea (evita spam si el job se ejecuta varias
 * veces antes de que cambie la fecha).
 *
 * Si el admin EDITA el registro y mueve la fecha al futuro, el sistema
 * borra la marca (el ReminderJob detecta que es nueva fecha y vuelve a
 * mandar).
 */
@Entity
@Table(name = "email_reminder_sent",
       uniqueConstraints = @UniqueConstraint(columnNames = {"table_id", "record_id"}),
       indexes = {
           @Index(name = "idx_reminder_record", columnList = "record_id"),
       })
@Data
public class EmailReminderSent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    /** Snapshot del valor de la fecha para detectar si el admin la cambió. */
    @Column(name = "for_date", length = 40)
    private String forDate;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }
}
