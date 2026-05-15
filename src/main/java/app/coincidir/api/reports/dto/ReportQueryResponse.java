package app.coincidir.api.reports.dto;

import java.util.List;
import java.util.Map;

public record ReportQueryResponse(
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        Long rowCount,
        String sqlDebug
) {
    public record ColumnMeta(
            String key,
            String label,
            String type
    ) {}
}
