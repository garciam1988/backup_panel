package app.coincidir.api.web.dto.logging;

import java.time.Instant;

public record ClientLogEventDto(
        Long id,
        Instant serverTs,
        Instant clientTs,
        String level,
        String category,
        String app,
        String env,
        String sessionId,
        String requestId,
        Long userId,
        String userEmail,
        String userRole,
        String url,
        String pathname,
        String userAgent,
        String platform,
        String ip,
        String message,
        String dataJson,
        String breadcrumbsJson
) {
}
