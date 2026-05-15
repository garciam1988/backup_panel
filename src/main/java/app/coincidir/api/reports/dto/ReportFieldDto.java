package app.coincidir.api.reports.dto;

import java.util.List;

public record ReportFieldDto(
        String key,
        String label,
        String type,
        List<String> allowedAggs
) {}
