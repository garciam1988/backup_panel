package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * NotificationLog — Auditoría unificada de TODO lo que se envió al cliente.
 *
 * Centraliza envíos de campañas, notificaciones transaccionales (stamp
 * sumado, premio listo, cupón generado, etc.) y automatizaciones
 * (recordatorio de cumpleaños, alerta de inactividad). Sirve para:
 *   - Auditoría regulatoria (qué se mandó, cuándo, a quién).
 *   - Debugging de envíos fallidos.
 *   - Métricas agregadas de delivery / engagement.
 */
@Entity
@Table(name = "notification_log")
@Getter @Setter
public class NotificationLog {

    public enum SourceType { CAMPAIGN, TRANSACTIONAL, AUTOMATION, MANUAL }
    public enum Channel { WHATSAPP, EMAIL, WEB_PUSH, SMS }
    public enum Status { QUEUED, SENT, DELIVERED, READ, FAILED, BOUNCED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL solo en envíos broadcast sin destinatario individual. */
    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    /** ID del recurso origen: campaignId, ruleId, etc. Texto libre. */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "payload_json", columnDefinition = "JSON")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "provider", length = 40)
    private String provider;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    void onCreate() {
        if (queuedAt == null) queuedAt = Instant.now();
    }
}
