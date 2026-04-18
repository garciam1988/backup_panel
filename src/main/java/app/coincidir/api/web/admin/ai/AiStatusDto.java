package app.coincidir.api.web.admin.ai;

import java.time.Instant;

public record AiStatusDto(
        boolean running,
        int errorCount,
        int warningCount,
        int okCount,
        int total,
        Instant lastRunAt
) {}
