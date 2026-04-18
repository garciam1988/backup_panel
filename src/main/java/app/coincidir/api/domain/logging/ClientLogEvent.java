package app.coincidir.api.domain.logging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity(name = "ClientLogEventLogging")
@Table(name = "client_log_event")
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

    @Column(length = 10, nullable = false)
    private String level;

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

    @PrePersist
    public void prePersist() {
        if (serverTs == null) {
            serverTs = Instant.now();
        }
    }
}
