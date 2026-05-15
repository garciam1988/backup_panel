package app.coincidir.api.reports.service;

import java.util.EnumSet;

public record ReportField(
        String key,
        String label,
        ReportFieldType type,
        EnumSet<ReportAgg> allowedAggs
) {
    public static ReportField of(String key, String label, ReportFieldType type, EnumSet<ReportAgg> allowedAggs) {
        return new ReportField(key, label, type, allowedAggs);
    }
}
