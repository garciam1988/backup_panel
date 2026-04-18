package app.coincidir.api.web.admin.ai;

import java.time.Instant;

public record AirFlightAlertStatusDto(
        boolean running,
        Instant lastRunAt,
        String lastRunStatus,
        int totalFlights,
        int issueCount
) {}
