package app.coincidir.api.web.dto;

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
        String userId,
        String userEmail,
        String userRole,
        String ip,
        String userAgent,
        String url,
        String pathname,
        String message,
        String dataJson,
        String breadcrumbsJson
) {
}
