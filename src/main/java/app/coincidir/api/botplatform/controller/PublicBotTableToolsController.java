package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.service.BotTableService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PublicBotTableToolsController — endpoints públicos (sin auth) para que
 * el CoinBot pueda:
 *   - Listar las tools genéricas de tablas (list_bot_tables, query_records, ...)
 *   - Ejecutar una tool con args + flag confirmed
 *   - Saber si una tool requiere confirmación humana antes de ejecutarse
 *
 * Esto se integra con el sistema de tools del bot junto a las API tools.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-table-tools")
@RequiredArgsConstructor
public class PublicBotTableToolsController {

    private final BotTableService service;
    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    @Transactional(readOnly = true)
    public ToolsResponse list() {
        ToolsResponse resp = new ToolsResponse();
        resp.tools = new ArrayList<>();
        resp.meta = new ArrayList<>();

        for (BotTableService.ToolDef td : service.buildToolsForBot()) {
            ToolEntry t = new ToolEntry();
            t.name = td.name;
            t.description = "[BotTables] " + td.description;
            t.input_schema = td.inputSchema;
            resp.tools.add(t);

            ToolMeta m = new ToolMeta();
            m.name = td.name;
            m.kind = "bot_table";
            // requireConfirmation depende de tabla → se evalúa al ejecutar
            m.dynamicConfirmation = "add_record".equals(td.name)
                    || "update_record".equals(td.name)
                    || "delete_record".equals(td.name);
            resp.meta.add(m);
        }
        return resp;
    }

    @PostMapping("/execute")
    @Transactional
    public ExecuteResponse execute(@RequestBody ExecuteRequest req) {
        ExecuteResponse resp = new ExecuteResponse();
        if (req == null || req.toolName == null) {
            resp.ok = false; resp.output = "toolName requerido";
            return resp;
        }
        JsonNode args = req.args != null ? req.args : objectMapper.createObjectNode();
        BotTableService.ToolResult r = service.executeTool(
                req.toolName, args, Boolean.TRUE.equals(req.confirmed), req.sessionId);
        resp.ok = r.ok;
        resp.output = r.output;
        resp.requiresConfirmation = r.requiresConfirmation;
        resp.confirmAction = r.confirmAction;
        return resp;
    }

    /**
     * Pregunta si una tool sobre cierta tabla requiere confirmación humana.
     * El bot lo consulta antes de ejecutar para saber si pedir aprobación al usuario.
     */
    @GetMapping("/needs-confirm")
    public NeedsConfirmResponse needsConfirm(
            @RequestParam String toolName,
            @RequestParam(required = false) String tableSlug) {
        NeedsConfirmResponse r = new NeedsConfirmResponse();
        // list_bot_tables, query_records y get_record_detail nunca piden confirmación (read-only)
        if ("list_bot_tables".equals(toolName)
                || "query_records".equals(toolName)
                || "get_record_detail".equals(toolName)) {
            r.needsConfirm = false;
            return r;
        }
        if (tableSlug == null || tableSlug.isBlank()) {
            r.needsConfirm = false;
            return r;
        }
        String action = service.getConfirmActionForTool(toolName, tableSlug);
        r.needsConfirm = action != null;
        r.action = action;
        return r;
    }

    /**
     * Devuelve el contenido de las tablas que el admin marcó como
     * "inject_to_prompt = true", serializado como markdown table.
     * El bot lo inyecta al system prompt en cada mensaje.
     */
    @GetMapping("/prompt-context")
    @Transactional(readOnly = true)
    public PromptContextResponse promptContext() {
        PromptContextResponse resp = new PromptContextResponse();
        resp.tables = new ArrayList<>();
        for (BotTable t : tableRepo.findByActiveTrueOrderByNameAsc()) {
            if (!Boolean.TRUE.equals(t.getInjectToPrompt())) continue;
            try {
                PromptTableEntry entry = new PromptTableEntry();
                entry.slug = t.getSlug();
                entry.name = t.getName();
                entry.description = t.getDescription();
                entry.content = serializeTableAsMarkdown(t);
                resp.tables.add(entry);
            } catch (Exception e) {
                log.warn("[prompt-context] error serializando '{}': {}", t.getSlug(), e.getMessage());
            }
        }
        return resp;
    }

    /**
     * Lista todas las tablas activas (sin records). Usado por el menú
     * digital del admin para que el dropdown "Origen del menú" pueda
     * mostrar las tablas como opción además de los catálogos Excel.
     */
    @GetMapping("/tables")
    @Transactional(readOnly = true)
    public PublicTablesResponse listTables() {
        PublicTablesResponse resp = new PublicTablesResponse();
        resp.tables = new ArrayList<>();
        for (BotTable t : tableRepo.findByActiveTrueOrderByNameAsc()) {
            PublicTableEntry e = new PublicTableEntry();
            e.slug = t.getSlug();
            e.name = t.getName();
            e.description = t.getDescription();
            try {
                JsonNode cols = objectMapper.readTree(t.getColumnsJson());
                List<String> colList = new ArrayList<>();
                for (JsonNode c : cols) colList.add(c.get("name").asText());
                e.columns = colList;
            } catch (Exception ex) {
                e.columns = List.of();
            }
            resp.tables.add(e);
        }
        return resp;
    }

    /**
     * Devuelve TODOS los records activos de una tabla en formato JSON.
     * Usado por el menú digital para pintar items como si fueran filas
     * de un Excel.
     *
     * Importante: este endpoint es público (sin auth) porque el menú
     * digital se sirve a clientes anónimos. Mismo modelo que el de
     * Excel catalog (que también es accesible públicamente para que el
     * menú visual del bot funcione sin login).
     */
    @GetMapping("/{slug}/records")
    @Transactional(readOnly = true)
    public PublicRecordsResponse getRecords(@PathVariable String slug) {
        PublicRecordsResponse resp = new PublicRecordsResponse();
        resp.rows = new ArrayList<>();

        BotTable t = tableRepo.findBySlug(slug)
                .filter(bt -> Boolean.TRUE.equals(bt.getActive()))
                .orElse(null);
        if (t == null) {
            // No tirar 404 — devolvemos vacío para que el front muestre
            // "menú no disponible" sin romper.
            return resp;
        }
        resp.tableSlug = t.getSlug();
        resp.tableName = t.getName();
        try {
            JsonNode cols = objectMapper.readTree(t.getColumnsJson());
            List<String> colList = new ArrayList<>();
            for (JsonNode c : cols) colList.add(c.get("name").asText());
            resp.columns = colList;
        } catch (Exception ignore) {
            resp.columns = List.of();
        }

        for (BotTableRecord r : recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId())) {
            try {
                PublicRow row = new PublicRow();
                row.id = r.getId();
                row.data = objectMapper.readTree(r.getDataJson());
                resp.rows.add(row);
            } catch (Exception ex) {
                log.warn("[public-records] error parseando record {}: {}", r.getId(), ex.getMessage());
            }
        }
        return resp;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicTablesResponse {
        public List<PublicTableEntry> tables;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicTableEntry {
        public String slug;
        public String name;
        public String description;
        public List<String> columns;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicRecordsResponse {
        public String tableSlug;
        public String tableName;
        public List<String> columns;
        public List<PublicRow> rows;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicRow {
        public Long id;
        public JsonNode data;
    }

    private String serializeTableAsMarkdown(BotTable t) throws Exception {
        com.fasterxml.jackson.databind.JsonNode cols = objectMapper.readTree(t.getColumnsJson());
        List<String> allColNames = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode c : cols) allColNames.add(c.get("name").asText());

        // Si la tabla tiene injectFields configurado, filtramos las columnas a
        // inyectar. Las columnas no listadas NO se inyectan (el bot las consulta
        // vía get_record_detail si las necesita). Esto reduce mucho el tamaño
        // del prompt en catálogos grandes.
        List<String> colNames;
        String injectFields = t.getInjectFields();
        if (injectFields != null && !injectFields.isBlank()) {
            java.util.Set<String> allowed = new java.util.HashSet<>();
            for (String f : injectFields.split(",")) {
                String trimmed = f.trim();
                if (!trimmed.isEmpty()) allowed.add(trimmed);
            }
            colNames = new ArrayList<>();
            for (String c : allColNames) if (allowed.contains(c)) colNames.add(c);
            // Fallback defensivo: si el filtro no matchea ninguna columna real
            // (admin tipeó mal los nombres), volvemos al comportamiento original.
            if (colNames.isEmpty()) colNames = allColNames;
        } else {
            colNames = allColNames;
        }

        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("| id | ");
        sb.append(String.join(" | ", colNames));
        sb.append(" |\n|---|");
        for (int i = 0; i < colNames.size(); i++) sb.append("---|");
        sb.append("\n");

        List<BotTableRecord> recs = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        // Cap defensivo: si la tabla tiene >500 registros, dejamos solo los 500 más recientes
        // y avisamos al final. Es para que el prompt no explote silenciosamente.
        int cap = 500;
        boolean truncated = recs.size() > cap;
        int limit = Math.min(recs.size(), cap);
        for (int i = 0; i < limit; i++) {
            BotTableRecord r = recs.get(i);
            com.fasterxml.jackson.databind.JsonNode data = objectMapper.readTree(r.getDataJson());
            sb.append("| ").append(r.getId()).append(" | ");
            for (int ci = 0; ci < colNames.size(); ci++) {
                if (ci > 0) sb.append(" | ");
                com.fasterxml.jackson.databind.JsonNode v = data.get(colNames.get(ci));
                String s = (v == null || v.isNull()) ? "" : v.asText();
                sb.append(s.replace("|", "/").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        if (truncated) {
            sb.append("\n*(mostrando los últimos ").append(cap)
              .append(" registros — la tabla tiene ").append(recs.size())
              .append(". Para ver más usá la tool query_records con esta slug.)*\n");
        }
        return sb.toString();
    }

    // ─────── DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptContextResponse {
        public List<PromptTableEntry> tables;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptTableEntry {
        public String slug;
        public String name;
        public String description;
        public String content;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolsResponse {
        public List<ToolEntry> tools;
        public List<ToolMeta> meta;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolEntry {
        public String name;
        public String description;
        public JsonNode input_schema;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolMeta {
        public String name;
        public String kind;
        /** Si true, el bot tiene que consultar /needs-confirm porque depende de la tabla. */
        public Boolean dynamicConfirmation;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteRequest {
        public String toolName;
        public JsonNode args;
        public Boolean confirmed;
        /** ID de sesión del chat. Sirve para asociar el record creado a la
         *  sesión que lo originó (necesario para mensajes proactivos). */
        public String sessionId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteResponse {
        public Boolean ok;
        public String output;
        public Boolean requiresConfirmation;
        public String confirmAction;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NeedsConfirmResponse {
        public Boolean needsConfirm;
        public String action;
    }
}
