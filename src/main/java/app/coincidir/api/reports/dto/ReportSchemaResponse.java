package app.coincidir.api.reports.dto;

import java.util.List;

public record ReportSchemaResponse(
        String panel,
        List<ReportDataSourceDto> dataSources
) {}
