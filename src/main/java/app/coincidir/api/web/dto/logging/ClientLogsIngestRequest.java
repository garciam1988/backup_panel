package app.coincidir.api.web.dto.logging;

import java.util.List;

public record ClientLogsIngestRequest(
        String app,
        String env,
        String sessionId,
        User user,
        Device device,
        Context context,
        List<LogItem> logs
) {
    public record User(
            Long id,
            String email,
            String role
    ) {}

    public record Device(
            String userAgent,
            String platform
    ) {}

    public record Context(
            String url,
            String pathname,
            String screen
    ) {}

    public record LogItem(
            String ts,
            String level,
            String category,
            String message,
            Object data,
            String requestId,
            Object breadcrumbs
    ) {}
}
