package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * DataSourceMigrationService — migra una Fuente de datos (Excel/CSV) a
 * una Tabla del bot, preservando los datos. Después del migrate:
 *   - Se crea una nueva BotTable con columnas auto-tipadas
 *   - Cada fila de la fuente se inserta como BotTableRecord (source="import")
 *   - injectToPrompt = true e injectFields se setea según preferencia
 *   - La Fuente original queda DESACTIVADA (no borrada — recuperable)
 *
 * Beneficio principal: permite usar la feature injectFields para mandar al
 * prompt SOLO algunas columnas (ej: producto/precio), reduciendo costo.
 * Las columnas no inyectadas siguen accesibles para el bot vía la tool
 * get_record_detail.
 *
 * Auto-tipado de columnas:
 *   - Si TODOS los valores no-vacíos parsean como número → "number"
 *   - Si TODOS son "true"/"false"/"si"/"no" (case-insensitive) → "boolean"
 *   - Caso contrario → "text"
 * Heurística simple, suficiente para Excel típicos. Si falla, el admin puede
 * editar la columna después en el editor de tabla.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceMigrationService {

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogRowRepository rowRepo;
    private final BotTableRepository botTableRepo;
    private final BotTableRecordRepository botRecordRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");
    private static final Pattern COL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_ ]{0,49}$");
    private static final Set<String> BOOLEAN_TRUE = Set.of(
            "true", "si", "sí", "yes", "y", "1", "verdadero", "v"
    );
    private static final Set<String> BOOLEAN_FALSE = Set.of(
            "false", "no", "n", "0", "falso", "f"
    );

    /**
     * Genera una preview de la migración: devuelve el slug propuesto, las
     * columnas detectadas con sus tipos auto-detectados, y el conteo de
     * filas a migrar. NO modifica nada — para mostrar en el modal del admin
     * antes de confirmar.
     */
    @Transactional(readOnly = true)
    public MigrationPreview preview(Long catalogId) {
        ExcelCatalog cat = catalogRepo.findById(catalogId)
                .orElseThrow(() -> new IllegalArgumentException("Fuente no encontrada"));
        validateMigratable(cat);

        List<ExcelCatalogRow> rows = rowRepo.findByCatalogIdOrderBySheetNameAscRowIndexAsc(catalogId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("La fuente no tiene filas para migrar");

        // Tomamos solo la primera hoja por simplicidad. Si el Excel tiene
        // múltiples hojas con esquemas distintos, esto no aplica bien — el
        // admin puede migrar las otras hojas a mano más adelante (raro
        // en la práctica).
        String firstSheet = rows.get(0).getSheetName();
        List<ExcelCatalogRow> sheetRows = rows.stream()
                .filter(r -> firstSheet.equals(r.getSheetName()))
                .toList();

        List<DetectedColumn> columns = detectColumns(sheetRows);
        MigrationPreview p = new MigrationPreview();
        p.suggestedSlug = generateSlug(cat.getName());
        p.suggestedName = humanize(cat.getName());
        p.firstSheet = firstSheet;
        p.totalRows = sheetRows.size();
        p.totalSheets = (int) rows.stream().map(ExcelCatalogRow::getSheetName).distinct().count();
        p.columns = columns;
        return p;
    }

    /**
     * Ejecuta la migración. Crea la BotTable + records, y desactiva la
     * Fuente original. Idempotente al nivel de slug: si ya existe una
     * BotTable con ese slug, falla.
     */
    @Transactional
    public MigrationResult migrate(Long catalogId, MigrationRequest req) {
        ExcelCatalog cat = catalogRepo.findById(catalogId)
                .orElseThrow(() -> new IllegalArgumentException("Fuente no encontrada"));
        validateMigratable(cat);

        // 1) Validar slug único
        String slug = (req.slug != null && !req.slug.isBlank())
                ? req.slug.trim().toLowerCase()
                : generateSlug(cat.getName());
        if (!SLUG_PATTERN.matcher(slug).matches())
            throw new IllegalArgumentException("Slug inválido. Debe ser snake_case, empezar con letra. Recibido: " + slug);
        if (botTableRepo.findBySlug(slug).isPresent())
            throw new IllegalArgumentException("Ya existe una Tabla del bot con slug '" + slug + "'. Elegí otro.");

        String tableName = (req.name != null && !req.name.isBlank()) ? req.name.trim() : humanize(cat.getName());

        // 2) Cargar filas (primera hoja)
        List<ExcelCatalogRow> allRows = rowRepo.findByCatalogIdOrderBySheetNameAscRowIndexAsc(catalogId);
        if (allRows.isEmpty())
            throw new IllegalArgumentException("La fuente no tiene filas para migrar");
        String firstSheet = allRows.get(0).getSheetName();
        List<ExcelCatalogRow> sheetRows = allRows.stream()
                .filter(r -> firstSheet.equals(r.getSheetName()))
                .toList();

        // 3) Detectar columnas y validar nombres
        List<DetectedColumn> columns = detectColumns(sheetRows);
        if (columns.isEmpty())
            throw new IllegalArgumentException("No se detectaron columnas válidas en la fuente");

        // Construir columnsJson para BotTable
        ArrayNode cols = objectMapper.createArrayNode();
        for (DetectedColumn dc : columns) {
            // Sanitizar nombre — los nombres con caracteres raros del Excel los
            // simplificamos para que pasen COL_NAME_PATTERN del schema validator.
            String safeName = sanitizeColumnName(dc.name);
            if (!COL_NAME_PATTERN.matcher(safeName).matches()) {
                log.warn("Columna '{}' tiene nombre inválido, se omite", dc.name);
                continue;
            }
            ObjectNode col = objectMapper.createObjectNode();
            col.put("name", safeName);
            col.put("type", dc.type);
            col.put("required", false);
            cols.add(col);
            // Mapear safeName de vuelta para usar al insertar records
            dc.safeName = safeName;
        }
        if (cols.isEmpty())
            throw new IllegalArgumentException("No quedó ninguna columna válida después de sanitizar nombres");

        // 4) Crear BotTable
        BotTable bt = new BotTable();
        bt.setName(tableName);
        bt.setSlug(slug);
        bt.setDescription("Migrado desde Fuente de datos: " + cat.getName()
                + (cat.getDescription() != null ? " · " + cat.getDescription() : ""));
        bt.setColumnsJson(cols.toString());
        bt.setActive(true);
        bt.setConfirmAdd(false);
        bt.setConfirmUpdate(true);
        bt.setConfirmDelete(true);
        bt.setInjectToPrompt(true);
        // injectFields: si el admin pasó una lista, usarla; si no, null = todas
        if (req.injectFields != null && !req.injectFields.isBlank()) {
            bt.setInjectFields(req.injectFields.trim());
        }
        bt = botTableRepo.save(bt);

        // 5) Migrar las filas
        int inserted = 0;
        for (ExcelCatalogRow row : sheetRows) {
            try {
                JsonNode original = objectMapper.readTree(row.getDataJson());
                ObjectNode target = objectMapper.createObjectNode();
                for (DetectedColumn dc : columns) {
                    if (dc.safeName == null) continue; // columna que se omitió
                    JsonNode v = original.get(dc.name);
                    if (v == null || v.isNull()) continue;
                    // Convertir según el tipo detectado para mantener consistencia
                    if ("number".equals(dc.type)) {
                        Double d = parseDouble(v.asText());
                        if (d != null) target.put(dc.safeName, d);
                    } else if ("boolean".equals(dc.type)) {
                        Boolean b = parseBoolean(v.asText());
                        if (b != null) target.put(dc.safeName, b);
                    } else {
                        target.put(dc.safeName, v.asText());
                    }
                }
                if (target.size() == 0) continue;
                BotTableRecord rec = new BotTableRecord();
                rec.setTableId(bt.getId());
                rec.setDataJson(target.toString());
                rec.setSource("import");
                botRecordRepo.save(rec);
                inserted++;
            } catch (Exception e) {
                log.warn("[MigrateDS] error en fila {}: {}", row.getRowIndex(), e.getMessage());
            }
        }

        // 6) Desactivar fuente original (no borrar)
        cat.setActive(false);
        catalogRepo.save(cat);

        log.info("[MigrateDS] Fuente '{}' (id={}) migrada a BotTable '{}' (id={}, slug='{}'). " +
                 "Filas migradas: {}/{}",
                cat.getName(), cat.getId(), bt.getName(), bt.getId(), bt.getSlug(),
                inserted, sheetRows.size());

        MigrationResult result = new MigrationResult();
        result.botTableId = bt.getId();
        result.botTableSlug = bt.getSlug();
        result.botTableName = bt.getName();
        result.rowsInserted = inserted;
        result.rowsTotal = sheetRows.size();
        return result;
    }

    /**
     * Detecta columnas del primer record de la hoja (las claves del JSON)
     * y para cada una analiza todos los valores para inferir el tipo.
     */
    private List<DetectedColumn> detectColumns(List<ExcelCatalogRow> rows) {
        if (rows.isEmpty()) return List.of();

        // Primero, recolectar nombres de columnas en orden de aparición.
        // Usamos LinkedHashMap para preservar el orden del primer record.
        LinkedHashMap<String, ColumnAnalyzer> analyzers = new LinkedHashMap<>();
        try {
            JsonNode firstData = objectMapper.readTree(rows.get(0).getDataJson());
            firstData.fieldNames().forEachRemaining(name -> {
                if (name != null && !name.isBlank()) {
                    analyzers.put(name, new ColumnAnalyzer(name));
                }
            });
        } catch (Exception e) {
            log.warn("Error leyendo primer record: {}", e.getMessage());
            return List.of();
        }

        // Luego, recorrer todos los records sumando observaciones a cada analyzer
        for (ExcelCatalogRow row : rows) {
            try {
                JsonNode data = objectMapper.readTree(row.getDataJson());
                for (Map.Entry<String, ColumnAnalyzer> entry : analyzers.entrySet()) {
                    JsonNode v = data.get(entry.getKey());
                    if (v == null || v.isNull()) continue;
                    String text = v.asText();
                    if (text == null || text.isBlank()) continue;
                    entry.getValue().observe(text);
                }
            } catch (Exception ignored) {}
        }

        List<DetectedColumn> out = new ArrayList<>();
        for (ColumnAnalyzer a : analyzers.values()) {
            DetectedColumn c = new DetectedColumn();
            c.name = a.name;
            c.type = a.inferType();
            c.sampleValues = a.samples;
            out.add(c);
        }
        return out;
    }

    private void validateMigratable(ExcelCatalog cat) {
        if (cat.getMimeType() != null) {
            String mt = cat.getMimeType().toLowerCase();
            // Excel + CSV son válidos. PDF, Word, imágenes no son tabulares.
            boolean isExcelOrCsv = mt.contains("excel") || mt.contains("spreadsheet")
                    || mt.contains("csv") || mt.contains("text/csv")
                    || mt.endsWith("/vnd.ms-excel")
                    || mt.endsWith("/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            if (!isExcelOrCsv) {
                throw new IllegalArgumentException(
                        "Solo se pueden migrar fuentes Excel o CSV. Esta fuente es: " + mt);
            }
        }
        // Si no hay mime type, asumimos que es Excel (caso común en uploads
        // viejos donde no se guardaba el mime). El check real es que la
        // tabla excel_catalog_row tenga filas.
    }

    private String generateSlug(String name) {
        if (name == null) return "tabla_migrada";
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = "t_" + s;
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    private String humanize(String name) {
        if (name == null || name.isBlank()) return "Tabla migrada";
        // "catalogo_brasas_argentinas" → "Catalogo Brasas Argentinas"
        String s = name.replace("_", " ").replace("-", " ").trim();
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) { upper = true; sb.append(c); }
            else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    private String sanitizeColumnName(String raw) {
        if (raw == null) return "";
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^A-Za-z0-9_ ]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_ ]+|[_ ]+$", "")
                .trim();
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = "col_" + s;
        if (s.length() > 50) s = s.substring(0, 50);
        return s;
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.replace(",", ".").trim()); }
        catch (Exception e) { return null; }
    }

    private static Boolean parseBoolean(String s) {
        if (s == null) return null;
        String low = s.trim().toLowerCase();
        if (BOOLEAN_TRUE.contains(low)) return true;
        if (BOOLEAN_FALSE.contains(low)) return false;
        return null;
    }

    /** Analizador acumulativo del tipo de una columna. */
    private static class ColumnAnalyzer {
        final String name;
        int total = 0;
        int numericCount = 0;
        int booleanCount = 0;
        List<String> samples = new ArrayList<>();

        ColumnAnalyzer(String name) { this.name = name; }

        void observe(String text) {
            total++;
            if (samples.size() < 3) samples.add(text);
            if (parseDouble(text) != null) numericCount++;
            if (parseBoolean(text) != null) booleanCount++;
        }

        String inferType() {
            if (total == 0) return "text";
            // Umbral del 90% para tipos no-text — tolerante a outliers
            if ((double) numericCount / total >= 0.9) return "number";
            if ((double) booleanCount / total >= 0.9) return "boolean";
            return "text";
        }
    }

    // ─── DTOs ───

    public static class MigrationPreview {
        public String suggestedSlug;
        public String suggestedName;
        public String firstSheet;
        public Integer totalRows;
        public Integer totalSheets;
        public List<DetectedColumn> columns;
    }

    public static class DetectedColumn {
        public String name;
        public String type;       // "text" | "number" | "boolean"
        public List<String> sampleValues;
        // Interno: nombre saneado para BotTable
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String safeName;
    }

    public static class MigrationRequest {
        public String slug;
        public String name;
        public String injectFields; // CSV de columnas. null/vacío = inyectar todas
    }

    public static class MigrationResult {
        public Long botTableId;
        public String botTableSlug;
        public String botTableName;
        public Integer rowsInserted;
        public Integer rowsTotal;
    }
}
