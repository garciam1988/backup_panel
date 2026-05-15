package app.coincidir.api.reports.dto;

import java.util.List;

public record ReportQueryRequest(
        String panel,
        String dataSourceId,
        String mode, // detail | aggregate
        List<String> columns,
        List<String> dimensions,
        List<Metric> metrics,
        List<Filter> filters,
        List<Sort> sort,
        Integer limit,
        Integer offset
) {
    public record Metric(
            String id,
            String agg,
            String field,
            String label
    ) {}

    public record Filter(
            String field,
            String op,
            Object value,
            Object value2
    ) {}

    public record Sort(
            String field,
            String dir
    ) {}
}
