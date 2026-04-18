package app.coincidir.api.reports.service;

import app.coincidir.api.reports.dto.ReportQueryRequest;
import app.coincidir.api.reports.dto.ReportQueryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ReportRegistry registry;

    public ReportQueryService(JdbcTemplate jdbcTemplate, ReportRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    public ReportQueryResponse query(ReportQueryRequest req) {
        if (req == null) throw new app.coincidir.api.common.exception.BadRequestException("Body requerido");
        String dataSourceId = trimToNull(req.dataSourceId());
        if (dataSourceId == null) throw new app.coincidir.api.common.exception.BadRequestException("dataSourceId requerido");

        ReportDataSource ds = registry.getOrThrow(dataSourceId);
        String mode = Optional.ofNullable(trimToNull(req.mode())).orElse("detail").toLowerCase(Locale.ROOT);

        SqlBuild b;
        if ("aggregate".equals(mode)) {
            b = buildAggregate(ds, req);
        } else {
            b = buildDetail(ds, req);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(b.sql, b.params.toArray());

        // columnas para respuesta (keys + tipos)
        List<ReportQueryResponse.ColumnMeta> cols = new ArrayList<>();
        for (SelectedCol c : b.selectedCols) {
            cols.add(new ReportQueryResponse.ColumnMeta(c.key, c.label, c.type));
        }

        return new ReportQueryResponse(cols, rows, (long) rows.size(), null);
    }

    private SqlBuild buildDetail(ReportDataSource ds, ReportQueryRequest req) {
        List<String> colKeys = safeFieldList(ds, req.columns());
        if (colKeys.isEmpty()) {
            // default: todas las columnas
            colKeys = ds.fields().stream().map(ReportField::key).toList();
        }

        List<SelectedCol> selected = new ArrayList<>();
        List<String> selectSql = new ArrayList<>();

        for (String k : colKeys) {
            ReportField f = ds.byKey().get(k);
            if (f == null) continue;
            selectSql.add("t.`" + f.key() + "` AS `" + f.key() + "`");
            selected.add(new SelectedCol(f.key(), f.label(), f.type().name()));
        }

        if (selectSql.isEmpty()) {
            throw new app.coincidir.api.common.exception.BadRequestException("No hay columnas seleccionadas validas");
        }

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT ").append(String.join(", ", selectSql))
                .append(" FROM ").append(ds.fromSql()).append(" t");

        appendWhere(ds, req.filters(), sql, params);
        appendOrderBy(ds, req.sort(), sql);

        int limit = Optional.ofNullable(req.limit()).orElse(500);
        int offset = Optional.ofNullable(req.offset()).orElse(0);
        limit = Math.max(1, Math.min(limit, 5000));
        offset = Math.max(0, offset);

        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return new SqlBuild(sql.toString(), params, selected);
    }

    private SqlBuild buildAggregate(ReportDataSource ds, ReportQueryRequest req) {
        List<String> dims = safeFieldList(ds, req.dimensions());
        List<ReportQueryRequest.Metric> metrics = Optional.ofNullable(req.metrics()).orElse(List.of());

        if (metrics.isEmpty()) {
            metrics = List.of(new ReportQueryRequest.Metric("count_all", "COUNT", null, "Cantidad"));
        }

        List<String> selectSql = new ArrayList<>();
        List<SelectedCol> selected = new ArrayList<>();

        for (String k : dims) {
            ReportField f = ds.byKey().get(k);
            if (f == null) continue;
            selectSql.add("t.`" + f.key() + "` AS `" + f.key() + "`");
            selected.add(new SelectedCol(f.key(), f.label(), f.type().name()));
        }

        for (ReportQueryRequest.Metric m : metrics) {
            if (m == null) continue;
            ReportAgg agg = parseAgg(m.agg());

            String fieldKey = trimToNull(m.field());
            ReportField field = fieldKey == null ? null : ds.byKey().get(fieldKey);

            // validar combinaciones
            if (agg == ReportAgg.COUNT) {
                // ok (con o sin campo)
            } else {
                if (field == null) {
                    throw new app.coincidir.api.common.exception.BadRequestException("Metric field requerido para agg: " + agg);
                }
                if (!field.allowedAggs().contains(agg)) {
                    throw new app.coincidir.api.common.exception.BadRequestException("Agg no permitido para campo " + field.key() + ": " + agg);
                }
            }

            String alias = safeAlias(trimToNull(m.id()), agg, fieldKey);
            String label = trimToNull(m.label());
            if (label == null) label = alias;

            String expr;
            switch (agg) {
                case COUNT -> {
                    if (field != null) expr = "COUNT(t.`" + field.key() + "`)";
                    else expr = "COUNT(*)";
                }
                case COUNT_DISTINCT -> expr = "COUNT(DISTINCT t.`" + field.key() + "`)";
                case SUM -> expr = "SUM(t.`" + field.key() + "`)";
                case AVG -> expr = "AVG(t.`" + field.key() + "`)";
                case MIN -> expr = "MIN(t.`" + field.key() + "`)";
                case MAX -> expr = "MAX(t.`" + field.key() + "`)";
                default -> throw new IllegalStateException("Unexpected agg: " + agg);
            }

            selectSql.add(expr + " AS `" + alias + "`");
            selected.add(new SelectedCol(alias, label, "NUMBER"));
        }

        if (selectSql.isEmpty()) throw new app.coincidir.api.common.exception.BadRequestException("No hay columnas/metricas validas");

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT ").append(String.join(", ", selectSql))
                .append(" FROM ").append(ds.fromSql()).append(" t");

        appendWhere(ds, req.filters(), sql, params);

        if (!dims.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dims.stream().map(k -> "t.`" + k + "`").toList()));
        }

        appendOrderByForAggregate(ds, req.sort(), sql, selected);

        int limit = Optional.ofNullable(req.limit()).orElse(500);
        int offset = Optional.ofNullable(req.offset()).orElse(0);
        limit = Math.max(1, Math.min(limit, 5000));
        offset = Math.max(0, offset);

        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return new SqlBuild(sql.toString(), params, selected);
    }

    private void appendWhere(ReportDataSource ds, List<ReportQueryRequest.Filter> filters, StringBuilder sql, List<Object> params) {
        List<ReportQueryRequest.Filter> fs = Optional.ofNullable(filters).orElse(List.of());
        List<String> parts = new ArrayList<>();
        for (ReportQueryRequest.Filter f : fs) {
            if (f == null) continue;
            String key = trimToNull(f.field());
            if (key == null) continue;
            ReportField field = ds.byKey().get(key);
            if (field == null) continue;

            String op = Optional.ofNullable(trimToNull(f.op())).orElse("eq").toLowerCase(Locale.ROOT);
            String col = "t.`" + field.key() + "`";

            switch (op) {
                case "eq" -> {
                    parts.add(col + " = ?");
                    params.add(f.value());
                }
                case "ne" -> {
                    parts.add(col + " <> ?");
                    params.add(f.value());
                }
                case "gt" -> {
                    parts.add(col + " > ?");
                    params.add(f.value());
                }
                case "gte" -> {
                    parts.add(col + " >= ?");
                    params.add(f.value());
                }
                case "lt" -> {
                    parts.add(col + " < ?");
                    params.add(f.value());
                }
                case "lte" -> {
                    parts.add(col + " <= ?");
                    params.add(f.value());
                }
                case "between" -> {
                    parts.add(col + " BETWEEN ? AND ?");
                    params.add(f.value());
                    params.add(f.value2());
                }
                case "contains" -> {
                    parts.add(col + " LIKE ?");
                    params.add("%" + safeString(f.value()) + "%");
                }
                case "startswith" -> {
                    parts.add(col + " LIKE ?");
                    params.add(safeString(f.value()) + "%");
                }
                case "endswith" -> {
                    parts.add(col + " LIKE ?");
                    params.add("%" + safeString(f.value()));
                }
                case "in" -> {
                    List<Object> vals = toList(f.value());
                    if (vals.isEmpty()) continue;
                    parts.add(col + " IN (" + String.join(",", Collections.nCopies(vals.size(), "?")) + ")");
                    params.addAll(vals);
                }
                case "notin" -> {
                    List<Object> vals = toList(f.value());
                    if (vals.isEmpty()) continue;
                    parts.add(col + " NOT IN (" + String.join(",", Collections.nCopies(vals.size(), "?")) + ")");
                    params.addAll(vals);
                }
                case "isnull" -> parts.add(col + " IS NULL");
                case "notnull" -> parts.add(col + " IS NOT NULL");
                default -> throw new app.coincidir.api.common.exception.BadRequestException("Filtro op no soportado: " + op);
            }
        }

        if (!parts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", parts));
        }
    }

    private void appendOrderBy(ReportDataSource ds, List<ReportQueryRequest.Sort> sort, StringBuilder sql) {
        List<ReportQueryRequest.Sort> ss = Optional.ofNullable(sort).orElse(List.of());
        List<String> parts = new ArrayList<>();
        for (ReportQueryRequest.Sort s : ss) {
            if (s == null) continue;
            String key = trimToNull(s.field());
            if (key == null) continue;
            if (!ds.byKey().containsKey(key)) continue;
            String dir = Optional.ofNullable(trimToNull(s.dir())).orElse("asc").toLowerCase(Locale.ROOT);
            if (!dir.equals("asc") && !dir.equals("desc")) dir = "asc";
            parts.add("t.`" + key + "` " + dir.toUpperCase(Locale.ROOT));
        }
        if (!parts.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", parts));
        }
    }

    private void appendOrderByForAggregate(ReportDataSource ds, List<ReportQueryRequest.Sort> sort, StringBuilder sql, List<SelectedCol> selectedCols) {
        List<ReportQueryRequest.Sort> ss = Optional.ofNullable(sort).orElse(List.of());
        List<String> parts = new ArrayList<>();
        Set<String> selectedKeys = new HashSet<>();
        for (SelectedCol c : selectedCols) selectedKeys.add(c.key);

        for (ReportQueryRequest.Sort s : ss) {
            if (s == null) continue;
            String key = trimToNull(s.field());
            if (key == null) continue;
            if (!selectedKeys.contains(key) && !ds.byKey().containsKey(key)) continue;
            String dir = Optional.ofNullable(trimToNull(s.dir())).orElse("asc").toLowerCase(Locale.ROOT);
            if (!dir.equals("asc") && !dir.equals("desc")) dir = "asc";
            parts.add("`" + key + "` " + dir.toUpperCase(Locale.ROOT));
        }
        if (!parts.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", parts));
        }
    }

    private static List<String> safeFieldList(ReportDataSource ds, List<String> list) {
        if (list == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String k : list) {
            String key = trimToNull(k);
            if (key == null) continue;
            if (ds.byKey().containsKey(key)) out.add(key);
        }
        return out;
    }

    private static ReportAgg parseAgg(String s) {
        String v = Optional.ofNullable(trimToNull(s)).orElse("COUNT").toUpperCase(Locale.ROOT);
        return switch (v) {
            case "COUNT" -> ReportAgg.COUNT;
            case "COUNT_DISTINCT", "COUNTDISTINCT" -> ReportAgg.COUNT_DISTINCT;
            case "SUM" -> ReportAgg.SUM;
            case "AVG", "AVERAGE" -> ReportAgg.AVG;
            case "MIN" -> ReportAgg.MIN;
            case "MAX" -> ReportAgg.MAX;
            default -> throw new app.coincidir.api.common.exception.BadRequestException("Agg no soportado: " + v);
        };
    }

    private static String safeAlias(String id, ReportAgg agg, String field) {
        String base = id;
        if (base == null) {
            base = agg.name().toLowerCase(Locale.ROOT) + "_" + (field == null ? "all" : field);
        }
        // deja solo [a-zA-Z0-9_]
        String cleaned = base.replaceAll("[^a-zA-Z0-9_]", "_");
        if (cleaned.isBlank()) cleaned = "metric";
        return cleaned;
    }

    private static String safeString(Object v) {
        if (v == null) return "";
        return String.valueOf(v);
    }

    private static List<Object> toList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(o);
            return out;
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(java.lang.reflect.Array.get(v, i));
            return out;
        }
        if (v instanceof String s) {
            String[] parts = s.split(",");
            List<Object> out = new ArrayList<>();
            for (String p : parts) {
                String t = trimToNull(p);
                if (t != null) out.add(t);
            }
            return out;
        }
        return List.of(v);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private record SqlBuild(String sql, List<Object> params, List<SelectedCol> selectedCols) {}

    private record SelectedCol(String key, String label, String type) {}
}
