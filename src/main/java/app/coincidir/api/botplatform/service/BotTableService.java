package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.ProactiveRule;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Inyectado lazy para evitar ciclo (ProactiveRuleService podría depender
     *  de servicios que dependen de este). */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ProactiveRuleService proactiveRuleService;
    @org.springframework.beans.factory.annotation.Autowired
    private app.coincidir.api.botplatform.repository.ProactiveRuleRepository proactiveRuleRepo;

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
                boolean auto = col.path("auto").asBoolean(false);
                JsonNode val = data != null ? data.get(name) : null;

                // Columnas auto-generadas: ignoramos cualquier valor que mande el LLM
                // (puede haberse inventado uno) y NO validamos required acá, porque
                // el sistema rellena este campo post-insert vía applyAutoColumns().
                // Si el LLM no mandó nada, perfecto. Si mandó algo, lo descartamos.
                if (auto) continue;

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

    /**
     * Rellena las columnas marcadas como `auto: true` en el schema. Se invoca
     * POST-insert (cuando el record ya tiene id generado) y POST-update si
     * algún campo dependiente cambió.
     *
     * Soporta un campo opcional `autoTemplate` en cada columna con tokens:
     *   {id}            → id del record (post-insert)
     *   {date}          → fecha de creación como yyyy-MM-dd
     *   {time}          → hora de creación como HH:mm
     *   {field:nombre}  → valor de OTRA columna del record (útil para fecha_display
     *                     formateando una columna `fecha_y_hora_reserva`)
     *
     * Si una columna `auto` no tiene `autoTemplate`, se usa el `id` plano como
     * default razonable — eso cubre el caso típico de `numero_de_reserva`.
     *
     * Devuelve el JSON actualizado del record (con las columnas auto rellenas).
     * Si no hay columnas auto, devuelve el dataJson tal cual sin tocar nada.
     */
    public String applyAutoColumns(BotTable table, BotTableRecord record) {
        try {
            JsonNode schema = objectMapper.readTree(table.getColumnsJson());
            ObjectNode data = (ObjectNode) objectMapper.readTree(record.getDataJson());
            boolean changed = false;

            for (JsonNode col : schema) {
                if (!col.path("auto").asBoolean(false)) continue;
                String name = col.get("name").asText();
                String tpl = col.path("autoTemplate").asText("{id}");
                String value = renderAutoTemplate(tpl, record, data);
                // Solo seteamos si difiere de lo que ya hay — evita updates innecesarios.
                JsonNode existing = data.get(name);
                if (existing == null || !value.equals(existing.asText())) {
                    data.put(name, value);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(data) : record.getDataJson();
        } catch (Exception e) {
            log.warn("[BotTable] applyAutoColumns falló para record {}: {}", record.getId(), e.getMessage());
            return record.getDataJson();
        }
    }

    private String renderAutoTemplate(String tpl, BotTableRecord record, JsonNode data) {
        if (tpl == null || tpl.isBlank()) return String.valueOf(record.getId());
        String result = tpl;
        // Tokens simples
        result = result.replace("{id}", String.valueOf(record.getId()));
        // Fecha/hora de creación (en UTC; si en el futuro necesitamos timezone local
        // del cliente, se podría tomar del program o del request).
        Instant createdAt = record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now();
        java.time.ZonedDateTime z = createdAt.atZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires"));
        result = result.replace("{date}", z.toLocalDate().toString());
        result = result.replace("{time}", String.format("%02d:%02d", z.getHour(), z.getMinute()));
        // {field:nombreDeColumna} — interpolación de OTRA columna del record.
        Pattern fieldRef = Pattern.compile("\\{field:([^}]+)\\}");
        java.util.regex.Matcher m = fieldRef.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fieldName = m.group(1);
            JsonNode v = data.get(fieldName);
            String replacement = v == null || v.isNull() ? "" : v.asText();
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
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

        // Construir descripción de tablas para que Claude sepa qué slugs existen.
        //
        // Las columnas con `auto: true` SE OMITEN del schema que ve el LLM. Son
        // campos que el sistema rellena automáticamente (ej: numero_de_reserva
        // generado del id, fecha_display formateada). Si las dejáramos visibles,
        // el LLM podría inventar valores con patrones plausibles (ej: timestamps
        // YYYYMMDDHHMM) que pisan al valor real que va a setear el sistema.
        StringBuilder tablesDesc = new StringBuilder("Tablas disponibles:\n");
        // Tracking: tablas que tienen columna de teléfono configurada en el
        // panel de admin. Si hay al menos una, agregamos al final un bloque
        // de instrucciones sobre cómo reconocer clientes recurrentes.
        java.util.List<String> tablesWithPhone = new java.util.ArrayList<>();
        for (BotTable t : active) {
            tablesDesc.append("- ").append(t.getSlug()).append(": ");
            if (t.getDescription() != null && !t.getDescription().isBlank())
                tablesDesc.append(t.getDescription()).append(" ");
            try {
                JsonNode cols = objectMapper.readTree(t.getColumnsJson());
                tablesDesc.append("[columnas: ");
                List<String> colList = new ArrayList<>();
                for (JsonNode c : cols) {
                    // Columnas auto-generadas: el sistema las llena, el LLM no las toca.
                    if (c.path("auto").asBoolean(false)) continue;
                    String type = c.get("type").asText();
                    String req = c.path("required").asBoolean(false) ? "*" : "";
                    colList.add(c.get("name").asText() + ":" + type + req);
                }
                tablesDesc.append(String.join(", ", colList)).append("]");
                // Si el admin marcó qué columna es el teléfono, lo señalamos
                // explícito para que el LLM sepa por dónde filtrar al
                // detectar clientes recurrentes (ver bloque al final).
                if (t.getPhoneColumn() != null && !t.getPhoneColumn().isBlank()) {
                    tablesDesc.append(" [phoneColumn: ").append(t.getPhoneColumn()).append("]");
                    tablesWithPhone.add(t.getSlug() + "→" + t.getPhoneColumn());
                }
                tablesDesc.append("\n");
            } catch (Exception e) { tablesDesc.append("[error parseando columnas]\n"); }
        }
        tablesDesc.append("\nIMPORTANTE: solo guardá información que el usuario te dio explícitamente. ")
                  .append("Si una columna obligatoria no tiene dato del usuario, PREGUNTÁ antes de invocar ")
                  .append("add_record. NO inventes códigos, identificadores, ni valores plausibles para campos ")
                  .append("que el usuario no mencionó. El sistema rellena automáticamente los campos que ")
                  .append("necesita (ids, números de reserva, etc).\n");

        // ── Bloque de "cliente recurrente" ────────────────────────────────
        //
        // Si al menos una tabla tiene phoneColumn configurada, agregamos
        // instrucciones específicas para que el bot detecte clientes que
        // ya reservaron antes y les ofrezca reutilizar sus datos en vez de
        // pedirle todo de nuevo. UX más cálida, además de evitar typos en
        // datos repetidos (nombre, email).
        //
        // Sólo se inyecta si hay al menos UNA tabla con phoneColumn — si no,
        // el bot opera en una vertical donde el teléfono no aplica (ej:
        // catálogo de productos, FAQ) y este bloque sería ruido en el prompt.
        //
        // Las claves del flujo:
        //   1) Pedir teléfono PRIMERO en cualquier creación de registro.
        //   2) Llamar query_records con filter por phoneColumn ANTES de
        //      preguntar más datos.
        //   3) Si matchea, mostrar los datos guardados y pedir confirmación.
        //   4) Si confirma, reutilizar TODOS los datos del registro previo
        //      excepto los específicos de la nueva reserva (fecha, hora,
        //      cantidad de personas, observaciones, etc).
        //   5) Si NO matchea o el cliente dice "no soy yo", proceder con
        //      el flujo normal de pedir cada dato.
        if (!tablesWithPhone.isEmpty()) {
            tablesDesc.append("\n═══ RECONOCIMIENTO DE CLIENTE RECURRENTE ═══\n");
            tablesDesc.append("Las siguientes tablas tienen columna de teléfono configurada y se usan ")
                      .append("para registrar clientes (reservas, pedidos, etc):\n");
            for (String tp : tablesWithPhone) {
                tablesDesc.append("  • ").append(tp).append("\n");
            }
            tablesDesc.append("\n")
                .append("FLUJO OBLIGATORIO cuando el cliente quiere crear un nuevo registro en una de esas tablas:\n")
                .append("\n")
                .append("1) Pedile el TELÉFONO antes que el resto de los datos.\n")
                .append("\n")
                .append("2) Una vez que tenés el teléfono, INMEDIATAMENTE llamá a query_records así:\n")
                .append("     query_records({\"table\": \"<slug>\", \"filter\": {\"<phoneColumn>\": \"<teléfono>\"}})\n")
                .append("   Esto chequea si el cliente ya reservó/registró antes.\n")
                .append("\n")
                .append("3) Si query_records DEVUELVE registros (matched > 0):\n")
                .append("   • Tomá el registro MÁS RECIENTE (los resultados vienen ordenados desc).\n")
                .append("   • Salúdalo cálidamente y mostrale los datos que tenés guardados, por ejemplo:\n")
                .append("       \"¡Hola! Veo que ya reservaste con nosotros antes 😊\n")
                .append("        Tengo estos datos:\n")
                .append("        • Nombre: Pedro Lopez\n")
                .append("        • Teléfono: +5491188889999\n")
                .append("        • Email: pedro@gmail.com\n")
                .append("        ¿Son correctos? Si cambió algo decime y lo actualizo.\"\n")
                .append("   • LISTÁ los campos del registro previo que son INFO DEL CLIENTE (nombre, email,\n")
                .append("     preferencias, restricciones alimentarias). NO listes los datos de la reserva\n")
                .append("     anterior (fecha, hora, mesa) — eso es de la reserva pasada, no del cliente.\n")
                .append("   • Esperá su confirmación explícita (\"sí\", \"correcto\", \"son esos\", etc).\n")
                .append("\n")
                .append("4) Si el cliente confirma:\n")
                .append("   • Reutilizá esos datos (nombre, email, preferencias) en el add_record nuevo.\n")
                .append("   • Pedile sólo lo que falta para la reserva PUNTUAL: fecha, hora, cantidad de\n")
                .append("     personas, ocasión especial, observaciones nuevas si las hay.\n")
                .append("\n")
                .append("5) Si el cliente dice que NO son correctos, o que NO es él:\n")
                .append("   • NO uses los datos del registro previo.\n")
                .append("   • Procedé con el flujo normal: pedile cada dato uno por uno.\n")
                .append("\n")
                .append("6) Si query_records DEVUELVE VACÍO (matched = 0):\n")
                .append("   • Es un cliente nuevo. Procedé con el flujo normal: pedile cada dato uno por uno.\n")
                .append("\n")
                .append("IMPORTANTE: nunca asumas la identidad sin confirmar. Mostrá los datos y dejá que el\n")
                .append("cliente confirme. Si dos personas comparten teléfono (caso familia), el cliente real\n")
                .append("te va a decir \"no soy yo, soy Juan\" y vos seguís el flujo normal con los datos nuevos.\n");
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

        tools.add(buildTool("get_record_detail",
            "Devuelve TODOS los campos de un registro específico, incluidos los que no se inyectan al prompt por defecto. " +
            "Útil cuando una tabla tiene injectFields configurado y solo se inyectan algunas columnas (ej: nombre y precio) — " +
            "usá esta tool para obtener el detalle completo (ej: descripción, ingredientes, tags) cuando el cliente pregunte. " +
            "Buscá por id (preferido) o por nombre exacto en una columna específica. " + tablesDescStr,
            "{\"type\":\"object\",\"properties\":{" +
                "\"table\":{\"type\":\"string\",\"description\":\"Slug de la tabla\"}," +
                "\"id\":{\"type\":\"integer\",\"description\":\"ID del registro (preferido si lo conocés)\"}," +
                "\"matchField\":{\"type\":\"string\",\"description\":\"Si no tenés id, nombre de columna para matchear (ej: 'nombre' o 'producto')\"}," +
                "\"matchValue\":{\"type\":\"string\",\"description\":\"Valor exacto a matchear en matchField\"}" +
            "},\"required\":[\"table\"]}"));

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
    @Transactional
    public ToolResult executeTool(String toolName, JsonNode args, boolean confirmed) {
        return executeTool(toolName, args, confirmed, null);
    }

    /** Versión con sessionId — usada por el endpoint público para asociar el
     *  record creado a la sesión del chat (necesario para reglas proactivas).
     *
     *  IMPORTANTE: @Transactional acá es CRÍTICO. Los listeners marcados como
     *  @TransactionalEventListener(AFTER_COMMIT) (ej: BotRecordToLoyaltyListener
     *  que sincroniza loyalty_customer desde reservas del bot, y
     *  BotTableEmailService que manda emails de confirmación) NO se ejecutan
     *  si el evento "created"/"updated"/"cancelled" se publica fuera de una
     *  transacción activa: Spring lo descarta silenciosamente con un log
     *  "No transaction is in progress" a nivel WARN.
     *
     *  Antes este service no era @Transactional y los enrolamientos loyalty
     *  desde reservas del bot se perdían — los paths del panel (PanelBot
     *  TableController, BotTableAdminController) sí funcionaban porque sus
     *  controllers SÍ son @Transactional. */
    @Transactional
    public ToolResult executeTool(String toolName, JsonNode args, boolean confirmed, String sessionId) {
        ToolResult r = new ToolResult();
        try {
            switch (toolName) {
                case "list_bot_tables":   return doListTables(r);
                case "query_records":     return doQuery(r, args);
                case "add_record":        return doAdd(r, args, confirmed, sessionId);
                case "update_record":     return doUpdate(r, args, confirmed);
                case "delete_record":     return doDelete(r, args, confirmed);
                case "get_record_detail": return doGetDetail(r, args);
                default:
                    r.ok = false;
                    r.output = "Tool desconocida: " + toolName;
                    return r;
            }
        } catch (SchemaError e) {
            // Antes devolvíamos un string "Error: ..." que el LLM a veces
            // interpretaba como "warning ignorable" y declaraba éxito de la
            // operación al usuario. Ahora devolvemos un JSON estructurado con
            // un hint explícito de comportamiento para que NO mienta al user.
            log.warn("[BotTable] SchemaError ejecutando {}: {}", toolName, e.getMessage());
            r.ok = false;
            try {
                ObjectNode err = objectMapper.createObjectNode();
                err.put("error", "schema_error");
                err.put("message", e.getMessage());
                err.put("hint", "La operación FALLÓ. NO le digas al usuario que se guardó. Pedile el dato faltante o corregí el valor inválido y reintentá.");
                r.output = err.toString();
            } catch (Exception ignored) {
                r.output = "Error: " + e.getMessage();
            }
            return r;
        } catch (Exception e) {
            log.warn("[BotTable] error ejecutando " + toolName, e);
            r.ok = false;
            try {
                ObjectNode err = objectMapper.createObjectNode();
                err.put("error", "execution_error");
                err.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                err.put("hint", "Ocurrió un error inesperado al ejecutar " + toolName + ". NO le digas al usuario que se guardó. Pedí disculpas y sugerí reintentar o contactar al equipo.");
                r.output = err.toString();
            } catch (Exception ignored) {
                r.output = "Error ejecutando " + toolName + ": " + e.getMessage();
            }
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

        // ── Tenancy: filtrar por branch del contexto ────────────────────
        //
        // Si hay contexto (caso normal: bot público o admin), traemos solo
        // los records de esa branch. Si NO hay contexto (caso edge: el caller
        // no pasó por el filter), caemos al comportamiento legacy y traemos
        // todo (con warning).
        BranchScope scope = BranchContext.current();
        List<BotTableRecord> all;
        if (scope != null) {
            all = recordRepo.findByTableIdAndBranchIdOrderByCreatedAtDesc(t.getId(), scope.getBranchId());
        } else if (BranchContext.isCrossBranch()) {
            log.debug("[BotTable] doQuery cross-branch (todas las sucursales): tabla={}", slug);
            all = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        } else {
            log.warn("[BotTable] doQuery sin BranchContext — devolviendo TODOS los records (tabla={})", slug);
            all = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        }

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

    private ToolResult doAdd(ToolResult r, JsonNode args, boolean confirmed, String sessionId) throws Exception {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);
        JsonNode data = args.path("data");

        // Logging defensivo: si el bot está fallando silenciosamente, este log
        // es el oro para diagnosticar. Loguea SIEMPRE el input antes de validar.
        if (log.isInfoEnabled()) {
            String dataStr = data == null ? "null" : data.toString();
            // Truncar para no spamear el log con payloads gigantes
            if (dataStr.length() > 1000) dataStr = dataStr.substring(0, 1000) + "...(truncated)";
            log.info("[BotTable] doAdd entrada: tabla={} session={} data={}", slug, sessionId, dataStr);
        }

        if (Boolean.TRUE.equals(t.getConfirmAdd()) && !confirmed) {
            r.requiresConfirmation = true;
            r.confirmAction = "add";
            r.ok = false;
            r.output = "Esta acción requiere confirmación del usuario.";
            return r;
        }

        String normalized;
        try {
            normalized = validateAndNormalizeRecord(t, data);
        } catch (SchemaError se) {
            // Si el validador rechaza, devolvemos un ToolResult de error CLARO.
            // Antes esto se propagaba como excepción y el wrapper del controller
            // hacía 500. Mejor: que el LLM reciba un JSON estructurado con el
            // error para que pueda preguntarle al usuario el dato que falta.
            log.warn("[BotTable] doAdd RECHAZADO por validador: tabla={} error={} data={}",
                    slug, se.getMessage(), data);
            r.ok = false;
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", "schema_error");
            err.put("message", se.getMessage());
            err.put("hint", "Faltan datos o son inválidos. Preguntale al usuario antes de reintentar. NO digas que ya guardaste la reserva — fallo.");
            r.output = err.toString();
            return r;
        }

        BotTableRecord rec = new BotTableRecord();
        rec.setTableId(t.getId());
        rec.setDataJson(normalized);
        rec.setSource("bot");
        if (sessionId != null && !sessionId.isBlank()) {
            rec.setSessionId(sessionId);
        }

        // ── Tenancy: asignar branch del contexto ─────────────────────────
        //
        // En el bot público, el frontend manda X-Branch-Id en cada request
        // (lo guardó en su estado cuando el cliente eligió sucursal).
        // En requests del admin, el filter también arma el contexto.
        //
        // Si NO hay contexto:
        //   - Si es modo cross-branch deliberado (DIOS + X-Branch-All=true):
        //     rechazamos. No tiene sentido crear un record sin saber a qué
        //     branch atribuírselo.
        //   - Si es ausencia accidental (job interno, etc): dejamos branchId
        //     null y logueamos warning. El bootstrap rellenará después.
        BranchScope scope = BranchContext.current();
        if (scope != null) {
            rec.setBranchId(scope.getBranchId());
        } else if (BranchContext.isCrossBranch()) {
            r.ok = false;
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", "cross_branch_write_not_allowed");
            err.put("message", "Estás en modo 'Todas las sucursales'. Para crear un registro elegí una sucursal específica primero.");
            r.output = err.toString();
            return r;
        } else {
            log.warn("[BotTable] doAdd sin BranchContext — record quedará sin branch_id (tabla={}). " +
                    "El bootstrap lo asignará a la default en el próximo arranque.", slug);
        }

        rec = recordRepo.save(rec);
        log.info("[BotTable] doAdd INSERT ok: tabla={} record_id={} branch_id={}",
                slug, rec.getId(), rec.getBranchId());

        // Aplicar columnas auto-generadas ahora que el record tiene id real.
        // Si hay alguna columna `auto: true`, calculamos su valor (usando id,
        // fecha de creación, o referencias a otros campos del record) y guardamos
        // de nuevo. Lo hacemos como un segundo save para que el id esté disponible
        // — algunas plantillas (ej: numero_de_reserva = {id}) lo necesitan.
        String withAuto = applyAutoColumns(t, rec);
        if (!withAuto.equals(rec.getDataJson())) {
            rec.setDataJson(withAuto);
            rec = recordRepo.save(rec);
            log.info("[BotTable] doAdd auto-columns aplicadas: tabla={} record_id={}", slug, rec.getId());
        }

        // Disparamos evento "created" — los listeners (ej: BotTableEmailService)
        // se enteran y mandan email si la tabla tiene template configurado.
        try {
            eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "created"));
            log.info("[BotTable] evento 'created' publicado: tabla={} record={}", t.getSlug(), rec.getId());
        } catch (Exception e) {
            log.warn("[BotTable] no pude publicar evento created: {}", e.getMessage());
        }

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

        // ── Tenancy: validar que el record pertenezca a la branch activa ──
        //
        // Bloque 7: si el request es modo cross-branch (DIOS + X-Branch-All),
        // rechazamos las escrituras. DIOS tiene que elegir una sucursal
        // específica para modificar datos (evita errores como editar la
        // reserva "incorrecta" porque venía data mezclada de varias).
        //
        // Si hay scope normal: validamos que el record pertenezca a esa branch.
        if (BranchContext.isCrossBranch()) {
            r.ok = false;
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", "cross_branch_write_not_allowed");
            err.put("message", "Estás en modo 'Todas las sucursales'. Para modificar un registro elegí la sucursal específica primero.");
            r.output = err.toString();
            return r;
        }
        BranchScope scope = BranchContext.current();
        if (scope != null && rec.getBranchId() != null
                && !rec.getBranchId().equals(scope.getBranchId())) {
            log.warn("[BotTable] doUpdate cross-branch BLOQUEADO: tabla={} record_id={} record_branch={} ctx_branch={}",
                    slug, id, rec.getBranchId(), scope.getBranchId());
            throw new SchemaError("Registro " + id + " no encontrado en tabla '" + slug + "'");
        }

        // Merge: cargar data actual + aplicar patch + validar todo
        ObjectNode merged = (ObjectNode) objectMapper.readTree(rec.getDataJson());
        if (patch != null && patch.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
            while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); merged.set(e.getKey(), e.getValue()); }
        }
        String normalized = validateAndNormalizeRecord(t, merged);
        rec.setDataJson(normalized);
        // Re-aplicar columnas auto: si por ejemplo `fecha_display` depende de
        // `fecha_y_hora_reserva` y esa cambió, el display debe actualizarse.
        // applyAutoColumns es idempotente y barato (no hace I/O), así que lo
        // corremos siempre en update.
        String withAuto = applyAutoColumns(t, rec);
        if (!withAuto.equals(rec.getDataJson())) {
            rec.setDataJson(withAuto);
        }
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

        // ── Tenancy: validar branch (igual que en doUpdate) ──────────────
        if (BranchContext.isCrossBranch()) {
            r.ok = false;
            r.output = "{\"error\":\"cross_branch_write_not_allowed\"," +
                       "\"message\":\"Estás en modo 'Todas las sucursales'. Para borrar un registro elegí la sucursal específica primero.\"}";
            return r;
        }
        BranchScope scope = BranchContext.current();
        if (scope != null && toDelete.getBranchId() != null
                && !toDelete.getBranchId().equals(scope.getBranchId())) {
            log.warn("[BotTable] doDelete cross-branch BLOQUEADO: tabla={} record_id={} record_branch={} ctx_branch={}",
                    slug, id, toDelete.getBranchId(), scope.getBranchId());
            throw new SchemaError("Registro " + id + " no encontrado en tabla '" + slug + "'");
        }

        // Disparamos evento "cancelled" ANTES de borrar — el listener necesita
        // poder leer los datos del registro (ej: el email del cliente para mandarle
        // la notificación de cancelación). El evento se publica sincrónicamente
        // pero el envío de email es @Async así que tiene tiempo de capturar el
        // record antes de que se borre la transacción.
        try { eventPublisher.publishEvent(new BotTableChangeEvent(t, toDelete, "cancelled")); }
        catch (Exception e) { log.warn("[BotTable] no pude publicar evento cancelled: {}", e.getMessage()); }

        recordRepo.delete(toDelete);

        // Reglas proactivas: si la tabla tiene reglas con contextColumn, limpiamos
        // las marcas de "ya disparado" para el contexto del record borrado. Así,
        // si el contexto vuelve a abrirse en el futuro (ej: la mesa 5 que se
        // cobró, recibe nuevos comensales más tarde), las reglas vuelven a poder
        // dispararse desde cero.
        try {
            List<ProactiveRule> rules = proactiveRuleRepo.findByTableIdOrderByIdAsc(t.getId());
            JsonNode deletedData = objectMapper.readTree(toDelete.getDataJson());
            Set<String> processedContexts = new HashSet<>();
            for (ProactiveRule rule : rules) {
                String ctxCol = rule.getContextColumn();
                if (ctxCol == null || ctxCol.isBlank()) continue;
                JsonNode v = deletedData.get(ctxCol);
                if (v == null || v.isNull()) continue;
                String contextKey = v.asText("").trim();
                if (contextKey.isEmpty()) continue;
                if (!processedContexts.add(contextKey)) continue;
                proactiveRuleService.clearFiredForContext(t.getId(), contextKey);
            }
        } catch (Exception e) {
            log.warn("[BotTable] error limpiando marcas proactivas: {}", e.getMessage());
        }

        r.ok = true;
        r.output = "{\"deleted\":true,\"id\":" + id + "}";
        return r;
    }

    /**
     * doGetDetail — devuelve TODOS los campos de un registro específico.
     * Útil cuando la tabla tiene injectFields configurado y el bot necesita
     * info que no está en el prompt (ej: descripción del producto). Búsqueda
     * por id (preferido) o por matchField/matchValue.
     */
    private ToolResult doGetDetail(ToolResult r, JsonNode args) throws Exception {
        String slug = args.path("table").asText("");
        BotTable t = mustFindTable(slug);

        // ── Tenancy: scope del request ──────────────────────────────────
        // Si hay contexto, filtramos por branch del scope; si no, fallback
        // a all (con warning, igual que en doQuery).
        BranchScope scope = BranchContext.current();

        BotTableRecord rec = null;
        if (args.has("id") && !args.path("id").isNull()) {
            long id = args.path("id").asLong();
            Optional<BotTableRecord> opt = recordRepo.findById(id);
            if (opt.isPresent() && opt.get().getTableId().equals(t.getId())) {
                BotTableRecord candidate = opt.get();
                // Validar branch antes de devolver
                if (scope == null || candidate.getBranchId() == null
                        || candidate.getBranchId().equals(scope.getBranchId())) {
                    rec = candidate;
                } else {
                    log.warn("[BotTable] doGetDetail cross-branch BLOQUEADO: tabla={} record_id={} record_branch={} ctx_branch={}",
                            slug, id, candidate.getBranchId(), scope.getBranchId());
                }
            }
        } else if (args.has("matchField") && args.has("matchValue")) {
            String field = args.path("matchField").asText("");
            String value = args.path("matchValue").asText("");
            if (!field.isBlank() && !value.isBlank()) {
                // Buscar solo dentro de la branch del scope (o todas si no hay scope)
                List<BotTableRecord> all = scope != null
                        ? recordRepo.findByTableIdAndBranchIdOrderByCreatedAtDesc(t.getId(), scope.getBranchId())
                        : recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
                String valueLower = value.toLowerCase();
                for (BotTableRecord candidate : all) {
                    JsonNode data = objectMapper.readTree(candidate.getDataJson());
                    JsonNode v = data.get(field);
                    if (v == null || v.isNull()) continue;
                    if (v.asText("").toLowerCase().equals(valueLower)) {
                        rec = candidate;
                        break;
                    }
                }
            }
        }

        if (rec == null) {
            r.ok = false;
            r.output = "{\"error\":\"Registro no encontrado. Si buscás por nombre, " +
                "asegurate que matchField sea una columna válida y matchValue exacto.\"}";
            return r;
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("id", rec.getId());
        out.set("data", objectMapper.readTree(rec.getDataJson()));
        r.ok = true;
        r.output = objectMapper.writeValueAsString(out);
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
