package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BotTableImportExportService — import/export de registros de BotTables
 * en formato Excel (.xlsx) y CSV.
 *
 * Export: serializa columnas + registros a XLSX o CSV con headers.
 * Import: lee la primera hoja/CSV, matchea headers contra el schema (case-
 * insensitive + sin acentos), valida cada fila con BotTableService, devuelve
 * un reporte con éxitos/fallos. Modo estricto = rollback si hay 1 error.
 * Modo tolerante = guarda lo válido y reporta el resto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotTableImportExportService {

    private final BotTableRecordRepository recordRepo;
    private final BotTableService tableService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public static class ImportRow {
        public int rowNumber;       // número de fila en el archivo (1-based, incluyendo header)
        public boolean ok;
        public String error;
    }

    public static class ImportReport {
        public int totalRows;
        public int imported;
        public int failed;
        public boolean strict;
        public boolean rolledBack; // true si era estricto y abortó
        public List<ImportRow> errors = new ArrayList<>(); // solo las filas con error
        public List<String> matchedColumns = new ArrayList<>();
        public List<String> unmatchedHeaders = new ArrayList<>();
        public List<String> missingRequired = new ArrayList<>();
    }

    // ─────────────────────────────────────────────────────────────────
    // EXPORT
    // ─────────────────────────────────────────────────────────────────

    /** Devuelve un .xlsx con el contenido de la tabla. */
    public byte[] exportToXlsx(BotTable table) throws Exception {
        List<JsonNode> columns = parseColumns(table);
        List<BotTableRecord> records = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(table.getName()));

            // Header row
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i).get("name").asText());
            }

            // Filas
            int rIdx = 1;
            for (BotTableRecord rec : records) {
                JsonNode data;
                try { data = objectMapper.readTree(rec.getDataJson()); }
                catch (Exception e) { continue; }
                Row row = sheet.createRow(rIdx++);
                for (int i = 0; i < columns.size(); i++) {
                    JsonNode col = columns.get(i);
                    String name = col.get("name").asText();
                    String type = col.get("type").asText();
                    JsonNode val = data.get(name);
                    if (val == null || val.isNull()) continue;
                    Cell cell = row.createCell(i);
                    switch (type) {
                        case "number":
                            cell.setCellValue(val.asDouble());
                            break;
                        case "boolean":
                            cell.setCellValue(val.asBoolean() ? "Sí" : "No");
                            break;
                        default:
                            cell.setCellValue(val.asText());
                    }
                }
            }

            // Auto-size de columnas (rápido para pocas)
            for (int i = 0; i < columns.size(); i++) {
                try { sheet.autoSizeColumn(i); } catch (Exception ignored) {}
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Igual que exportToXlsx pero SOLO con los registros cuya columna de fecha
     * de referencia (ej: "fecha y hora reserva") cae en el día de HOY según la
     * zona horaria dada. Pensado para el backup de contingencia de reservas:
     * un Excel liviano con las reservas del día, que se sube a Git cada X min.
     *
     * Tolera columnas de tipo "date" (yyyy-MM-dd) y "datetime"
     * (yyyy-MM-ddTHH:mm:ss, con o sin Z/offset, o con espacio en vez de T):
     * extrae siempre la parte de fecha y la compara contra hoy.
     *
     * NOTA sobre zona horaria: el bot guarda los datetime en hora LOCAL sin Z
     * (ver BotTableService.validateAndNormalizeRecord, case "datetime"), así que
     * comparar la parte de fecha del string contra LocalDate.now(zone) es
     * correcto para el caso normal. Si algún valor viniera en UTC con Z, se
     * compara su fecha UTC tal cual — es un caso de borde que el bot no genera.
     *
     * Si dateColumnName es null/blank o no existe en el schema, se exporta la
     * tabla COMPLETA (degradación segura: mejor un backup de más que ninguno).
     *
     * @param table          la tabla a exportar (ej: Reservas)
     * @param dateColumnName nombre EXACTO de la columna de fecha en el schema
     * @param zone           zona horaria para determinar "hoy"
     */
    public byte[] exportTodayToXlsx(BotTable table, String dateColumnName, ZoneId zone) throws Exception {
        List<JsonNode> columns = parseColumns(table);
        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        // ¿La columna de fecha existe en el schema? Si no, no filtramos.
        boolean hasDateCol = dateColumnName != null && !dateColumnName.isBlank()
                && columns.stream().anyMatch(c -> dateColumnName.equals(c.get("name").asText()));

        LocalDate today = LocalDate.now(zone == null ? ZoneId.systemDefault() : zone);

        List<BotTableRecord> records = new ArrayList<>();
        if (!hasDateCol) {
            log.warn("[ReservasBackup] columna de fecha '{}' no existe en tabla '{}' — exporto tabla completa",
                    dateColumnName, table.getSlug());
            records = all;
        } else {
            for (BotTableRecord rec : all) {
                try {
                    JsonNode data = objectMapper.readTree(rec.getDataJson());
                    JsonNode v = data.get(dateColumnName);
                    if (v == null || v.isNull()) continue;
                    if (isSameDay(v.asText(), today)) records.add(rec);
                } catch (Exception ignore) { /* fila ilegible: la salteamos */ }
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(table.getName()));

            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i).get("name").asText());
            }

            int rIdx = 1;
            for (BotTableRecord rec : records) {
                JsonNode data;
                try { data = objectMapper.readTree(rec.getDataJson()); }
                catch (Exception e) { continue; }
                Row row = sheet.createRow(rIdx++);
                for (int i = 0; i < columns.size(); i++) {
                    JsonNode col = columns.get(i);
                    String name = col.get("name").asText();
                    String type = col.get("type").asText();
                    JsonNode val = data.get(name);
                    if (val == null || val.isNull()) continue;
                    Cell cell = row.createCell(i);
                    switch (type) {
                        case "number":
                            cell.setCellValue(val.asDouble());
                            break;
                        case "boolean":
                            cell.setCellValue(val.asBoolean() ? "Sí" : "No");
                            break;
                        default:
                            cell.setCellValue(val.asText());
                    }
                }
            }

            for (int i = 0; i < columns.size(); i++) {
                try { sheet.autoSizeColumn(i); } catch (Exception ignored) {}
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Extrae la parte de fecha (yyyy-MM-dd) de un valor date/datetime y la
     * compara contra {@code today}. Devuelve false ante null/blank/no-parseable
     * (nunca lanza — un valor roto no debe tumbar el backup).
     */
    private static boolean isSameDay(String raw, LocalDate today) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty()) return false;
        int tIdx = s.indexOf('T');
        if (tIdx < 0) tIdx = s.indexOf(' '); // tolera "2026-05-28 23:00:00"
        String datePart = tIdx > 0 ? s.substring(0, tIdx) : s;
        try {
            return LocalDate.parse(datePart).isEqual(today); // LocalDate.parse exige ISO yyyy-MM-dd
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cuenta cuántos registros de la tabla caen HOY según la columna de fecha.
     * Reusa la misma lógica de filtro que exportTodayToXlsx. Si la columna no
     * existe, devuelve el total (consistente con el fallback del export).
     */
    public int countTodayRecords(BotTable table, String dateColumnName, ZoneId zone) throws Exception {
        List<JsonNode> columns = parseColumns(table);
        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        boolean hasDateCol = dateColumnName != null && !dateColumnName.isBlank()
                && columns.stream().anyMatch(c -> dateColumnName.equals(c.get("name").asText()));
        if (!hasDateCol) return all.size();

        LocalDate today = LocalDate.now(zone == null ? ZoneId.systemDefault() : zone);
        int count = 0;
        for (BotTableRecord rec : all) {
            try {
                JsonNode data = objectMapper.readTree(rec.getDataJson());
                JsonNode v = data.get(dateColumnName);
                if (v != null && !v.isNull() && isSameDay(v.asText(), today)) count++;
            } catch (Exception ignore) { /* fila ilegible */ }
        }
        return count;
    }

    public byte[] exportToCsv(BotTable table) throws Exception {
        List<JsonNode> columns = parseColumns(table);
        List<BotTableRecord> records = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        StringBuilder sb = new StringBuilder();
        // BOM UTF-8 para que Excel detecte el encoding correcto
        sb.append('\uFEFF');
        // Header
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(columns.get(i).get("name").asText()));
        }
        sb.append("\r\n");

        for (BotTableRecord rec : records) {
            JsonNode data;
            try { data = objectMapper.readTree(rec.getDataJson()); }
            catch (Exception e) { continue; }
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(',');
                JsonNode col = columns.get(i);
                String name = col.get("name").asText();
                String type = col.get("type").asText();
                JsonNode val = data.get(name);
                if (val == null || val.isNull()) continue;
                String s;
                if ("boolean".equals(type)) s = val.asBoolean() ? "Sí" : "No";
                else s = val.asText();
                sb.append(csvEscape(s));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public ImportReport importFromXlsx(BotTable table, byte[] content, boolean strict) throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                ImportReport r = new ImportReport();
                r.errors.add(errorRow(0, "El archivo no tiene hojas"));
                return r;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> rowData = new ArrayList<>();
                int last = row.getLastCellNum();
                for (int i = 0; i < last; i++) {
                    Cell c = row.getCell(i);
                    rowData.add(cellToString(c));
                }
                rows.add(rowData);
            }
            return importRows(table, rows, strict);
        }
    }

    @Transactional
    public ImportReport importFromCsv(BotTable table, byte[] content, boolean strict) {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            List<List<String>> rows = parseCsv(reader);
            return importRows(table, rows, strict);
        } catch (Exception e) {
            ImportReport r = new ImportReport();
            r.errors.add(errorRow(0, "Error leyendo CSV: " + e.getMessage()));
            return r;
        }
    }

    private ImportReport importRows(BotTable table, List<List<String>> rows, boolean strict) {
        ImportReport report = new ImportReport();
        report.strict = strict;

        if (rows.isEmpty()) {
            report.errors.add(errorRow(0, "El archivo está vacío"));
            return report;
        }

        // Header (primera fila con contenido)
        List<String> headers = rows.get(0);
        if (headers.isEmpty() || headers.stream().allMatch(s -> s == null || s.isBlank())) {
            report.errors.add(errorRow(1, "La primera fila debe tener los nombres de columnas"));
            return report;
        }

        // Schema
        List<JsonNode> columns;
        try { columns = parseColumns(table); }
        catch (Exception e) { report.errors.add(errorRow(0, "Schema inválido: " + e.getMessage())); return report; }

        // Map header → schema column name (case-insensitive + NFD)
        Map<Integer, JsonNode> colByIdx = new HashMap<>();
        Map<String, JsonNode> schemaByNorm = new HashMap<>();
        for (JsonNode col : columns) schemaByNorm.put(normalize(col.get("name").asText()), col);

        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h == null || h.isBlank()) continue;
            JsonNode col = schemaByNorm.get(normalize(h));
            if (col != null) {
                colByIdx.put(i, col);
                report.matchedColumns.add(col.get("name").asText());
            } else {
                report.unmatchedHeaders.add(h);
            }
        }

        // Verificar required
        for (JsonNode col : columns) {
            if (col.path("required").asBoolean(false)
                    && !report.matchedColumns.contains(col.get("name").asText())) {
                report.missingRequired.add(col.get("name").asText());
            }
        }
        if (!report.missingRequired.isEmpty()) {
            report.errors.add(errorRow(1,
                "Faltan columnas obligatorias en el archivo: " + String.join(", ", report.missingRequired)));
            return report;
        }

        // Procesar filas (saltando header)
        report.totalRows = Math.max(0, rows.size() - 1);
        List<BotTableRecord> toSave = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            // Skip filas vacías
            if (row.stream().allMatch(s -> s == null || s.isBlank())) {
                continue;
            }
            try {
                ObjectNode data = objectMapper.createObjectNode();
                for (Map.Entry<Integer, JsonNode> e : colByIdx.entrySet()) {
                    int idx = e.getKey();
                    if (idx >= row.size()) continue;
                    String raw = row.get(idx);
                    if (raw == null || raw.isBlank()) continue;
                    String name = e.getValue().get("name").asText();
                    String type = e.getValue().get("type").asText();
                    putTyped(data, name, raw.trim(), type);
                }
                String normalized = tableService.validateAndNormalizeRecord(table, data);
                BotTableRecord rec = new BotTableRecord();
                rec.setTableId(table.getId());
                rec.setDataJson(normalized);
                rec.setSource("import");
                toSave.add(rec);
            } catch (Exception ex) {
                ImportRow er = errorRow(i + 1, ex.getMessage());
                report.errors.add(er);
                report.failed++;
                if (strict) {
                    // Modo estricto: abortamos todo
                    report.imported = 0;
                    report.rolledBack = true;
                    return report;
                }
            }
        }

        // Guardar todo
        if (!toSave.isEmpty()) {
            recordRepo.saveAll(toSave);
        }
        report.imported = toSave.size();
        return report;
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private List<JsonNode> parseColumns(BotTable table) throws Exception {
        JsonNode arr = objectMapper.readTree(table.getColumnsJson());
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : arr) out.add(c);
        return out;
    }

    /** Convierte el raw string al tipo correcto antes de pasárselo a validateAndNormalize. */
    private void putTyped(ObjectNode data, String name, String raw, String type) {
        switch (type) {
            case "number":
                try { data.put(name, Double.parseDouble(raw.replace(",", "."))); }
                catch (NumberFormatException e) { data.put(name, raw); /* validador lo rechaza */ }
                break;
            case "boolean":
                String l = raw.toLowerCase();
                if (List.of("true","1","si","sí","yes","y","x","✓").contains(l)) data.put(name, true);
                else if (List.of("false","0","no","n").contains(l)) data.put(name, false);
                else data.put(name, raw); // que el validador lo rechace
                break;
            default:
                data.put(name, raw);
        }
    }

    private String cellToString(Cell cell) {
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING:  return cell.getStringCellValue();
                case BOOLEAN: return cell.getBooleanCellValue() ? "true" : "false";
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        // Si es solo fecha (sin tiempo) la formateamos como ISO date
                        java.util.Date d = cell.getDateCellValue();
                        if (d == null) return "";
                        java.time.ZonedDateTime z = d.toInstant().atZone(java.time.ZoneId.systemDefault());
                        if (z.getHour() == 0 && z.getMinute() == 0 && z.getSecond() == 0) {
                            return z.toLocalDate().format(DATE_ISO);
                        }
                        return z.toInstant().toString();
                    }
                    double n = cell.getNumericCellValue();
                    // Si es entero, sin decimales
                    if (n == Math.floor(n) && !Double.isInfinite(n))
                        return String.valueOf((long) n);
                    return String.valueOf(n);
                case FORMULA:
                    try { return cell.getStringCellValue(); }
                    catch (Exception e) {
                        try { return String.valueOf(cell.getNumericCellValue()); }
                        catch (Exception ex) { return ""; }
                    }
                default: return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Parser CSV mínimo con soporte de comillas, comas dentro de strings, escapes. */
    private List<List<String>> parseCsv(Reader reader) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> currentRow = new ArrayList<>();
        boolean inQuotes = false;
        int ch;
        boolean firstChar = true;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            // BOM
            if (firstChar && c == '\uFEFF') { firstChar = false; continue; }
            firstChar = false;

            if (inQuotes) {
                if (c == '"') {
                    // Lookahead para "" → " literal
                    int next = reader.read();
                    if (next == '"') current.append('"');
                    else {
                        inQuotes = false;
                        if (next != -1) {
                            // Reprocesar el char después de cerrar quotes
                            if (next == ',') {
                                currentRow.add(current.toString());
                                current.setLength(0);
                            } else if (next == '\r') {
                                // ignore
                            } else if (next == '\n') {
                                currentRow.add(current.toString());
                                current.setLength(0);
                                rows.add(currentRow);
                                currentRow = new ArrayList<>();
                            } else {
                                current.append((char) next);
                            }
                        }
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') {
                    currentRow.add(current.toString());
                    current.setLength(0);
                } else if (c == '\r') {
                    // ignore (manejamos solo \n como EOL)
                } else if (c == '\n') {
                    currentRow.add(current.toString());
                    current.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    current.append(c);
                }
            }
        }
        // Último campo / fila si no termina con \n
        if (current.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(current.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .trim();
    }

    /** Truncar y limpiar el nombre para que sea válido como sheet de Excel. */
    private static String safeSheetName(String name) {
        if (name == null || name.isBlank()) return "Datos";
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        if (s.length() > 31) s = s.substring(0, 31);
        return s;
    }

    private static ImportRow errorRow(int n, String msg) {
        ImportRow r = new ImportRow();
        r.rowNumber = n;
        r.ok = false;
        r.error = msg;
        return r;
    }
}
