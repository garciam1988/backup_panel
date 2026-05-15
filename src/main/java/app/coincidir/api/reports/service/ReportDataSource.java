package app.coincidir.api.reports.service;

import java.util.List;
import java.util.Map;

public record ReportDataSource(
        String id,
        String label,
        String description,
        String fromSql, // view o tabla a consultar
        List<ReportField> fields,
        Map<String, ReportField> byKey
) {}
