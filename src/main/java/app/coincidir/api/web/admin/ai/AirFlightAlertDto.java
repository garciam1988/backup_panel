package app.coincidir.api.web.admin.ai;

import java.time.Instant;
import java.time.LocalDate;

public record AirFlightAlertDto(
        Long id,
        Long groupId,
        Long menuItemId,
        String groupDestination,
        String menuItemDisplayName,
        String flightNumber,
        String airline,
        LocalDate departureDate,
        String departureTime,
        String origin,
        String destination,
        String aviationStatus,
        Integer aviationDelayMinutes,
        boolean aviationDataFound,
        String aiSeverity,
        String aiSummary,
        String aiSuggestion,
        boolean hasIssue,
        boolean dismissed,
        boolean ignoredPermanently,
        Instant lastCheckedAt
) {}
