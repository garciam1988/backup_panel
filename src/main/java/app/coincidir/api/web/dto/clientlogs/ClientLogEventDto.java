package app.coincidir.api.web.dto.clientlogs;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ClientLogEventDto {
    private Long id;
    private String createdAt;
    private String eventTs;
    private String level;
    private String category;
    private String app;
    private String env;
    private String sessionId;
    private String requestId;
    private String userId;
    private String userEmail;
    private String userRole;
    private String url;
    private String pathname;
    private String userAgent;
    private String ip;
    private String message;
    private String dataJson;
    private String breadcrumbsJson;
}
