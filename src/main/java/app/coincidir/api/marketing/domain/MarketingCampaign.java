package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * MarketingCampaign — Campaña de comunicación multicanal.
 *
 * Una campaña combina:
 *   - WHO:   a quién se manda (segmento guardado o filtro ad-hoc).
 *   - WHAT:  mensaje por cada canal habilitado.
 *   - WHEN:  scheduling (immediate / scheduled / recurring / triggered).
 *   - WHERE: canales (WhatsApp, Email, Web Push).
 *
 * Estados (status):
 *   - DRAFT, SCHEDULED, RUNNING, COMPLETED, CANCELLED, FAILED.
 *
 * Schedule types:
 *   - IMMEDIATE  Se ejecuta apenas se le pega "Enviar".
 *   - SCHEDULED  Se ejecuta en scheduled_at (single shot).
 *   - RECURRING  Cron repetitivo (recurrence_config_json).
 *   - TRIGGERED  Se ejecuta cuando ocurre un evento (cumpleaños, inactividad).
 */
@Entity
@Table(name = "marketing_campaign")
@Getter @Setter
public class MarketingCampaign {

    public enum ScheduleType { IMMEDIATE, SCHEDULED, RECURRING, TRIGGERED }
    public enum Status { DRAFT, SCHEDULED, RUNNING, COMPLETED, CANCELLED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Si se usa segmento guardado. Mutuamente excluyente con targetFilterJson. */
    @Column(name = "segment_id")
    private Long segmentId;

    /** Filtro ad-hoc (mismo formato que MarketingSegment.criteriaJson). */
    @Column(name = "target_filter_json", columnDefinition = "JSON")
    private String targetFilterJson;

    /** JSON array: ["whatsapp","email","web_push"]. */
    @Column(name = "channels_json", columnDefinition = "JSON", nullable = false)
    private String channelsJson;

    @Column(name = "message_whatsapp", columnDefinition = "TEXT")
    private String messageWhatsapp;

    @Column(name = "message_email_subject", length = 200)
    private String messageEmailSubject;

    @Column(name = "message_email_body", columnDefinition = "LONGTEXT")
    private String messageEmailBody;

    @Column(name = "message_push_title", length = 100)
    private String messagePushTitle;

    @Column(name = "message_push_body", length = 255)
    private String messagePushBody;

    @Column(name = "cta_url", length = 500)
    private String ctaUrl;

    @Column(name = "coupon_id")
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType = ScheduleType.IMMEDIATE;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /** Para RECURRING: { "cron":"0 9 * * MON", "timezone":"America/Argentina/Buenos_Aires" } */
    @Column(name = "recurrence_config_json", columnDefinition = "JSON")
    private String recurrenceConfigJson;

    /** Para TRIGGERED: { "event":"birthday","daysBefore":7 } */
    @Column(name = "trigger_config_json", columnDefinition = "JSON")
    private String triggerConfigJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "total_targeted", nullable = false)
    private Integer totalTargeted = 0;

    @Column(name = "total_sent", nullable = false)
    private Integer totalSent = 0;

    @Column(name = "total_delivered", nullable = false)
    private Integer totalDelivered = 0;

    @Column(name = "total_opened", nullable = false)
    private Integer totalOpened = 0;

    @Column(name = "total_clicked", nullable = false)
    private Integer totalClicked = 0;

    @Column(name = "total_converted", nullable = false)
    private Integer totalConverted = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
