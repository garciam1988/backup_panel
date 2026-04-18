package app.coincidir.api.web.dto;

import java.util.List;
import java.util.Map;

public record ClientLogsIngestRequest(
        String app,
        String env,
        String sessionId,
        ClientLogUser user,
        ClientLogDevice device,
        ClientLogContext context,
        List<ClientLogItem> logs
) {
    public record ClientLogUser(Object id, String email, Object role) {}
    public record ClientLogDevice(String userAgent, String platform) {}
    public record ClientLogContext(String url, String pathname, String screen) {}

    public record ClientLogItem(
            String ts,
            String level,
            String category,
            String message,
            Map<String, Object> data,
            String requestId,
            List<Map<String, Object>> breadcrumbs
    ) {}
}
