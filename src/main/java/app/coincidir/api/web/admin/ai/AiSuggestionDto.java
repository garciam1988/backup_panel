package app.coincidir.api.web.admin.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AiSuggestionDto(
        Long id,
        Long groupId,
        String groupDestination,
        LocalDate travelStartDate,
        LocalDate travelEndDate,
        String summary,
        String severity,
        Instant lastRunAt,
        List<FindingDto> findings
) {
    public record FindingDto(
            String type,      // ERROR | WARNING | INFO
            String title,
            String description,
            String suggestion
    ) {}
}
