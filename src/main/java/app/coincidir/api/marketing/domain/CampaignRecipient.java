package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * CampaignRecipient — Destinatario individual de una campaña.
 *
 * Una fila por (campaignId, customerId). Trackea por separado el estado de
 * cada canal porque un mismo destinatario puede recibir el mensaje por
 * WhatsApp Y por Email Y por Web Push en la misma campaña.
 *
 * Estados de cada canal (whatsapp_status / email_status / push_status):
 *   - NULL       Canal no habilitado para esta campaña (no se intentó)
 *   - PENDING    Se encoló para enviar
 *   - SENT       El provider aceptó el mensaje
 *   - DELIVERED  El provider reportó entrega
 *   - READ       El destinatario lo leyó
 *   - FAILED     Falló el envío
 */
@Entity
@Table(name = "campaign_recipient",
    uniqueConstraints = @UniqueConstraint(name = "uk_camp_recip",
                                          columnNames = {"campaign_id","customer_id"}))
@Getter @Setter
public class CampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "whatsapp_status", length = 20)
    private String whatsappStatus;

    @Column(name = "email_status", length = 20)
    private String emailStatus;

    @Column(name = "push_status", length = 20)
    private String pushStatus;

    @Column(name = "whatsapp_sent_at")
    private Instant whatsappSentAt;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "push_sent_at")
    private Instant pushSentAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "converted_at")
    private Instant convertedAt;

    /** Si la campaña genera ingreso medible. */
    @Column(name = "conversion_value", precision = 12, scale = 2)
    private BigDecimal conversionValue;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
