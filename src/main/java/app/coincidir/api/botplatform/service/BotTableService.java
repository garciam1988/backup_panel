package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * BotTableService — lógica de negocio de tablas custom del bot.
 *
 * Responsabilidades:
 *   - Validar schemas (columns_json) al crear/actualizar tabla.
 *   - Validar registros contra el schema antes de guardar.
 *   - Generar las "tools" de Claude para que pueda operar sobre las tablas.
 *   - Ejecutar add/update/delete/query desde el bot.
 *
 * El bot ve UNA tool genérica por acción (add_record, query_records, etc),
 * no una por tabla, para no inflar el listado de tools cuando hay muchas tablas.
 * Claude le pasa la tabla por slug en el primer argumento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotTableService {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final List<String> VALID_TYPES = List.of("text", "number", "date", "datetime", "boolean", "select");
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");
    private static final Pattern COL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_ ]{0,49}$");

    // ─────────────────────────────────────────────────────────────
    // Schema validation
    // ─────────────────────────────────────────────────────────────

    public static class SchemaError extends RuntimeException {
        public SchemaError(String msg) { super(msg); }
    }

    /**
     * Valida que el schema sea correcto. Tira SchemaError si no.
     * Devuelve la versión normalizada (con los campos default si faltan).
     */
    public String validateSchema(String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank())
            throw new SchemaError("El esquema no puede estar vacío");
        try {
            JsonNode arr = objectMapper.readTree(columnsJson);
            if (!arr.isArray()) throw new SchemaError("El esquema debe ser un array de columnas");
            if (arr.size() == 0) throw new SchemaError("Tenés que definir al menos una columna");
            if (arr.size() > 30) throw new SchemaError("Máximo 30 columnas por tabla");

            ArrayNode out = objectMapper.createArrayNode();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (JsonNode col : arr) {
                if (!col.isObject()) throw new SchemaError("Cada columna debe ser un objeto JSON");
                String name = col.path("name").asText("").trim();
                String type = col.path("type").asText("").trim().toLowerCase();
                boolean required = col.path("required").asBoolean(false);

                if (!COL_NAME_PATTERN.matcher(name).matches())
                    throw new SchemaError("Nombre de columna inválido: '" + name + "' — letras/números/_ (max 50)");
                if (!names.add(name.toLowerCase()))
                    throw new SchemaError("Columna duplicada: '" + name + "'");
                if (!VALID_TYPES.contains(type))
                    throw new SchemaError("Tipo inválido en '" + name + "': " + type + ". Válidos: " + VALID_TYPES);

                ObjectNode normalized = objectMapper.createObjectNode();
                normalized.put("name", name);
                normalized.put("type", type);
                normalized.put("required", required);

                if ("select".equals(type)) {
                    JsonNode opts = col.path("options");
                    if (!opts.isArray() || opts.size() == 0)
                        throw new SchemaError("Columna '" + name + "' tipo select necesita 'options' (array no vacío)");
                    ArrayNode optsOut = objectMapper.createArrayNode();
                    for (JsonNode o : opts) {
                        String s = o.asText("").trim();
                        if (s.isEmpty()) continue;
                        optsOut.add(s);
                    }
                    if (optsOut.size() == 0) throw new SchemaError("Columna '" + name + "' tipo select sin opciones válidas");
                    normalized.set("options", optsOut);
                }
                out.add(normalized);
            }
            return objectMapper.writeValueAsString(out);
        } catch (SchemaError e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaError("Schema inválido: " + e.getMessage());
        }
    }

    public void validateSlug(String slug) {
        if (!SLUG_PATTERN.matcher(slug == null ? "" : slug).matches()) {
            throw new SchemaError("Slug inválido: debe ser snake_case, empezar con letra (max 60)");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Record validation y normalización
    // ─────────────────────────────────────────────────────────────

    /**
     * Valida un dataJson contra el schema de la tabla y devuelve el JSON normalizado.
     * Tira SchemaError si hay valores inválidos o falta un required.
     */
    public String validateAndNormalizeRecord(BotTable table, JsonNode data) {
        try {
            JsonNode schema = objectMapper.readTree(table.getColumnsJson());
            ObjectNode out = objectMapper.createObjectNode();

            for (JsonNode col : schema) {
                String name = col.get("name").asText();
                String type = col.get("type").asText();
                boolean required = col.path("required").asBoolean(false);
                JsonNode val = data != null ? data.get(name) : null;

                // Required check
                if (val == null || val.isNull() || (val.isTextual() && val.asText().isBlank())) {
                    if (required) throw new SchemaError("Falta la columna requerida: '" + name + "'");
                    continue; // omitir nulls opcionales
                }

                // Type-specific normalization
                switch (type) {
                    case "text":
                        out.put(name, val.asText());
                        break;
                    case "number":
                        if (val.isNumber()) out.put(name, val.numberValue().toString());
                        else {
                            try { Double.parseDouble(val.asText()); out.put(name, val.asText()); }
                            catch (NumberFormatException e) { throw new SchemaError("'" + name + "' debe ser número"); }
                        }
                        // Convertir a number real
                        out.put(name, Double.parseDouble(out.get(name).asText()));
                        break;
                    case "boolean":
                        if (val.isBoolean()) out.put(name, val.asBoolean());
                        else {
                            String s = val.asText().toLowerCase();
                            if (List.of("true","1","si","sí","yes").contains(s)) out.put(name, true);
                            else if (List.of("false","0","no").contains(s)) out.put(name, false);
                            else throw new SchemaError("'" + name + "' debe ser true/false");
                        }
                        break;
                    case "date":
                        try {
                            String s = val.asText();
                            LocalDate.parse(s); // ISO yyyy-MM-dd
                            out.put(name, s);
                        } catch (Exception e) { throw new SchemaError("'" + name + "' debe ser fecha ISO yyyy-MM-dd"); }
                        break;
                    case "datetime":
                        try {
                            String s = val.asText();
                            // Aceptamos 2 formatos: con Z/offset (UTC) o LOCAL sin zona.
                            // Para uso personal, recomendamos LOCAL (lo que ve el usuario).
                            // Si Claude manda LOCAL ("2026-04-27T21:00:00"), lo guardamos tal cual.
                            // Si manda UTC ("2026-04-27T21:00:00Z"), también lo aceptamos.
                            try {
                                java.time.LocalDateTime.parse(s);
                                out.put(name, s);
                            } catch (Exception inner) {
                                Instant.parse(s);
                                out.put(name, s);
                            }
                        } catch (Exception e) { throw new SchemaError("'" + name + "' debe ser ISO datetime (ej: 2026-04-26T20:00:00 o 2026-04-26T20:00:00Z)"); }
                        break;
                    case "select":
                        String s = val.asText();
                        ArrayNode opts = (ArrayNode) col.get("options");
                        boolean found = false;
                        for (JsonNode o : opts) if (o.asText().equals(s)) { found = true; break; }
                        if (!found) throw new SchemaError("'" + name + "' debe ser una de las opciones de '" + name + "'");
                        out.put(name, s);
                        break;
                }
            }
            return objectMapper.writeValueAsString(out);
        } catch (SchemaError e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaError("Error validando registro: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Tool generation para Claude
    // ─────────────────────────────────────────────────────────────

    public static class ToolDef {
        public String name;
        public String description;
        public JsonNode inputSchema;
    }

    /**
     * Devuelve las 5 tools genéricas que el bot puede usar para operar
     * sobre cualquier tabla activa. Si no hay tablas activas, retorna lista
     * vacía para no aparecer como tools muertas en Claude.
     */
    public List<ToolDef> buildToolsForBot() {
        List<BotTable> active = tableRepo.findByActiveTrueOrderByNameAsc();
        if (active.isEmpty()) return List.of();

        // Construir descripción de tablas para que Claude sepa qué slugs existen
        StringBuilder tablesDesc = new StringBuilder("Tablas disponibles:\n");
        for (BotTable t : active) {
            tablesDesc.append("- ").append(t.getSlug()).append(": ");
            if (t.getDescription() != null && !t.getDescription().isBlank())
                tablesDesc.append(t.getDescription()).append(" ");
            try {
                JsonNode cols = objectMapper.readTree(t.getColumnsJson());
                tablesDesc.append("[columnas: ");
                List<String> colList = new ArrayList<>();
                for (JsonNode c : cols) {
                    String type = c.get("type").asText();
                    String req = c.path("required").asBoolean(false) ? "*" : "";
                    colList.add(c.get("name").asText() + ":" + type + req);
                }
                tablesDesc.append(String.join(", ", colList)).append("]\n");
            } catch (Exception e) { tablesDesc.append("[error parseando columnas]\n"); }
        }
        String tablesDescStr = tablesDesc.toString();

        List<ToolDef> tools = new ArrayList<>();

        tools.add(buildTool("list_bot_tables",
            "Lista todas las tablas custom disponibles del bot con sus columnas. Útil cuando el usuario pregunta qué datos podés consultar/guardar.",
            "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(buildTool("query_records",
            "Busca registros en una tabla custom del bot. " + tablesDescStr +
            " Si se pasa filter, hace match exacto sobre los campos indicados (AND lógico). Sin filter devuelve todos.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"table\":{\"type\":\"string\",\"description\":\"Slug de la tabla (ej: reservas)\"}," +
                "\"filter\":{\"type\":\"object\",\"description\":\"Pares clave/valor para filtrar (match exacto, AND lógico). Opcional.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Máximo de resultados (default 50, max 200)\",\"default\":50}" +
            "},\"required\":[\"table\"]}"));

        tools.add(buildTool("add_record",
            "Crea un nuevo registro en una tabla custom. " + tablesDescStr +
            " La data debe coincidir con el schema (campos required son obligatorios).",
            "{\"type\":\"object\",\"properties\":{" +
                "\"table\":{\"type\":\"string\",\"description\":\"Slug de la tabla\"}," +
                "\"data\":{\"type\":\"object\",\"description\":\"Datos del registro\"}" +
            "},\"required\":[\"table\",\"data\"]}"));

        tools.add(buildTool("update_record",
            "Actualiza un registro existente por su id. Solo se modifican los campos indicados (merge). " + tablesDescStr,
            "{\"type\":\"object\",\"properties\":{" +
                "\"table\":{\"type\":\"string\"}," +
                "\"id\":{\"type\":\"integer\",\"description\":\"ID del registro\"}," +
                "\"data\":{\"type\":\"object\",\"description\":\"Solo los campos a modificar\"}" +
            "},\"required\":[\"table\",\"id\",\"data\"]}"));

        tools.add(buildTool("delete_record",
            "Elimina un registro por su id. Acción irreversible. " + tablesDescStr,
            "{\"type\":\"object\",\"properties\":{" +
                "\"table\":{\"type\":\"string\"}," +
                "\"id\":{\"type\":\"integer\"}" +
            "},\"required\":[\"table\",\"id\"]}"));

        return tools;
    }

    private ToolDef buildTool(String name, String desc, String schemaJson) {
        ToolDef t = new ToolDef();
        t.name = name;
        t.description = desc;
        try { t.inputSchema = objectMapper.readTree(schemaJson); }
        catch (Exception e) { throw new RuntimeException(e); }
        return t;
    }

    // ─────────────────────────────────────────────────────────────
    // Ejecución de tools (cuando el bot las invoca)
    // ─────────────────────────────────────────────────────────────

    public static class ToolResult {
        public boolean ok;
        public String output;
        public Object data;
        // Indica si necesita confirmación humana antes de ejecutar.
        public boolean requiresConfirmation;
        public String confirmAction;  // "add" | "update" | "delete"
    }

    /** Ejecuta una tool. Si requiere confirmación, devuelve requiresConfirmation=true sin ejecutar. */
    public ToolResult executeTool(String toolName, JsonNode args, boolean confirmed) {
        ToolResult r = new ToolResult();
        try {
            switch (toolName) {
                case "list_bot_tables": return doListTables(r);
                case "query_records":   return doQuery(r, args);
                case "add_record":      return doAdd(r, args, confirmed);
                case "update_record":   return doUpdate(r, args, confirmed);
                case "delete_record":   return doDelete(r, args, confirmed);
                default:
                    r.ok = false;
                    r.output = "Tool desconocida: " + toolName;
                    return r;
            }
        } catch (SchemaError e) {
            r.ok = false;
            r.output = "Error: " + e.getMessage();
            return r;
        } catch (Exception e) {
            log.warn("[BotTable] error ejecutando " + toolName, e);
            r.ok = false;
            r.output = "Error ejecutando " + toolName + ": " + e.getMessage();
            return r;
        }
    }

    private ToolResult doListTables(ToolResult r) {
        List<BotTable> tables = tableRepo.findByActiveTrueOrderByNameAsc();
        ArrayNode arr = objectMapper.createArrayNode();
        for (BotTable t : tables) {
            ObjectNode tn = objectMapper.createObjectNode();
            tn.put("slug", t.getSlug());
            tn.put("name", t.getName());
            if (t.getDescription() != null) tn.put("description", t.getDescription());
            try { tn.set("columns", objectMapper.readTree(t.getColumnsJson())); } catch (Exception ignored) {}
            tn.put("recordCount", recordRepo.countByTableId(t.getId()));
            arr.add(tn);
        }
        r.ok = true;
        r.output = arr.toString();
        return r;
    }

    private ToolResult doQuery(ToolResult r, JsonNode args) throws Exception {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);
        int limit = Math.min(200, Math.max(1, args.path("limit").asInt(50)));
        JsonNode filter = args.path("filter");

        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        ArrayNode out = objectMapper.createArrayNode();
        int matched = 0;
        for (BotTableRecord rec : all) {
            JsonNode data = objectMapper.readTree(rec.getDataJson());
            if (matchesFilter(data, filter)) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", rec.getId());
                item.put("createdAt", rec.getCreatedAt().toString());
                item.set("data", data);
                out.add(item);
                if (++matched >= limit) break;
            }
        }
        r.ok = true;
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("table", slug);
        resp.put("matched", matched);
        resp.set("records", out);
        r.output = resp.toString();
        return r;
    }

    private boolean matchesFilter(JsonNode data, JsonNode filter) {
        if (filter == null || !filter.isObject() || filter.size() == 0) return true;
        Iterator<Map.Entry<String, JsonNode>> it = filter.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = data.get(e.getKey());
            if (v == null) return false;
            if (!String.valueOf(v.asText()).equalsIgnoreCase(e.getValue().asText())) return false;
        }
        return true;
    }

    private ToolResult doAdd(ToolResult r, JsonNode args, boolean confirmed) throws Exception {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);
        JsonNode data = args.path("data");

        if (Boolean.TRUE.equals(t.getConfirmAdd()) && !confirmed) {
            r.requiresConfirmation = true;
            r.confirmAction = "add";
            r.ok = false;
            r.output = "Esta acción requiere confirmación del usuario.";
            return r;
        }

        String normalized = validateAndNormalizeRecord(t, data);
        BotTableRecord rec = new BotTableRecord();
        rec.setTableId(t.getId());
        rec.setDataJson(normalized);
        rec.setSource("bot");
        rec = recordRepo.save(rec);

        // Disparamos evento "created" — los listeners (ej: BotTableEmailService)
        // se enteran y mandan email si la tabla tiene template configurado.
        try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "created")); }
        catch (Exception e) { log.warn("[BotTable] no pude publicar evento created: {}", e.getMessage()); }

        r.ok = true;
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("created", true);
        resp.put("id", rec.getId());
        resp.set("data", objectMapper.readTree(normalized));
        r.output = resp.toString();
        return r;
    }

    private ToolResult doUpdate(ToolResult r, JsonNode args, boolean confirmed) throws Exception {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);
        long id = args.path("id").asLong();
        JsonNode patch = args.path("data");

        if (Boolean.TRUE.equals(t.getConfirmUpdate()) && !confirmed) {
            r.requiresConfirmation = true;
            r.confirmAction = "update";
            r.ok = false;
            r.output = "Esta acción requiere confirmación del usuario.";
            return r;
        }

        Optional<BotTableRecord> opt = recordRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getTableId().equals(t.getId()))
            throw new SchemaError("Registro " + id + " no encontrado en tabla '" + slug + "'");

        BotTableRecord rec = opt.get();
        // Merge: cargar data actual + aplicar patch + validar todo
        ObjectNode merged = (ObjectNode) objectMapper.readTree(rec.getDataJson());
        if (patch != null && patch.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
            while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); merged.set(e.getKey(), e.getValue()); }
        }
        String normalized = validateAndNormalizeRecord(t, merged);
        rec.setDataJson(normalized);
        recordRepo.save(rec);

        try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "updated")); }
        catch (Exception e) { log.warn("[BotTable] no pude publicar evento updated: {}", e.getMessage()); }

        r.ok = true;
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("updated", true);
        resp.put("id", rec.getId());
        resp.set("data", objectMapper.readTree(normalized));
        r.output = resp.toString();
        return r;
    }

    private ToolResult doDelete(ToolResult r, JsonNode args, boolean confirmed) {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);
        long id = args.path("id").asLong();

        if (Boolean.TRUE.equals(t.getConfirmDelete()) && !confirmed) {
            r.requiresConfirmation = true;
            r.confirmAction = "delete";
            r.ok = false;
            r.output = "Esta acción requiere confirmación del usuario.";
            return r;
        }

        Optional<BotTableRecord> opt = recordRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getTableId().equals(t.getId()))
            throw new SchemaError("Registro " + id + " no encontrado en tabla '" + slug + "'");

        BotTableRecord toDelete = opt.get();
        // Disparamos evento "cancelled" ANTES de borrar — el listener necesita
        // poder leer los datos del registro (ej: el email del cliente para mandarle
        // la notificación de cancelación). El evento se publica sincrónicamente
        // pero el envío de email es @Async así que tiene tiempo de capturar el
        // record antes de que se borre la transacción.
        try { eventPublisher.publishEvent(new BotTableChangeEvent(t, toDelete, "cancelled")); }
        catch (Exception e) { log.warn("[BotTable] no pude publicar evento cancelled: {}", e.getMessage()); }

        recordRepo.delete(toDelete);

        r.ok = true;
        r.output = "{\"deleted\":true,\"id\":" + id + "}";
        return r;
    }

    public BotTable mustFindTable(String slug) {
        return tableRepo.findBySlug(slug)
            .filter(t -> Boolean.TRUE.equals(t.getActive()))
            .orElseThrow(() -> new SchemaError("Tabla '" + slug + "' no existe o está inactiva"));
    }

    /** Devuelve qué tipo de confirmación necesita una tool. null = no requiere. */
    public String getConfirmActionForTool(String toolName, String tableSlug) {
        Optional<BotTable> opt = tableRepo.findBySlug(tableSlug);
        if (opt.isEmpty()) return null;
        BotTable t = opt.get();
        switch (toolName) {
            case "add_record":    return Boolean.TRUE.equals(t.getConfirmAdd())    ? "add"    : null;
            case "update_record": return Boolean.TRUE.equals(t.getConfirmUpdate()) ? "update" : null;
            case "delete_record": return Boolean.TRUE.equals(t.getConfirmDelete()) ? "delete" : null;
            default: return null;
        }
    }
}
