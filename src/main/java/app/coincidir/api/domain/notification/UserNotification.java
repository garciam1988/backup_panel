package app.coincidir.api.domain.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "user_notification",
        indexes = {
                @Index(name = "idx_user_notif_recipient_created", columnList = "recipient_email,created_at"),
                @Index(name = "idx_user_notif_group", columnList = "group_id"),
                @Index(name = "idx_user_notif_menu_item", columnList = "menu_item_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "menu_item_id")
    private Long menuItemId;

    @Column(name = "service_code", length = 40)
    private String serviceCode;

    @Column(name = "service_label", length = 160)
    private String serviceLabel;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "link_url", length = 512)
    private String linkUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "send_error", columnDefinition = "TEXT")
    private String sendError;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
