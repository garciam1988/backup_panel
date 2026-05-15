package app.coincidir.api.reports.dto;

import java.util.List;

public record ReportDataSourceDto(
        String id,
        String label,
        String description,
        List<ReportFieldDto> fields
) {}
