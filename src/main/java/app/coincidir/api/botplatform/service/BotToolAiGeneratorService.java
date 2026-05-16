package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.domain.ConnectorTableDescription;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.ConnectorSchemaCacheRepository;
import app.coincidir.api.botplatform.repository.ConnectorTableDescriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.*;

/**
 * BotToolAiGeneratorService — pide a Claude que proponga BotTools a partir del
 * schema de un conector.
 *
 * Flujo:
 *   1. Carga schema cacheado + glossary + descripciones por tabla del conector.
 *   2. Saca samples chicos (3 filas) de las tablas más relevantes para que
 *      Claude vea valores reales.
 *   3. Manda todo a Claude (Sonnet por default, más smart para SQL).
 *   4. Claude devuelve un array de propuestas: nombre, descripción, SQL,
 *      schema JSON, categoría, operationType.
 *   5. Validamos cada propuesta (sintaxis SQL básica, schema JSON parseable,
 *      operationType QUERY).
 *   6. Devolvemos las propuestas al frontend SIN guardarlas — el admin las
 *      revisa y guarda las que quiere desde la UI.
 *
 * IMPORTANTE: este servicio NO crea {@link app.coincidir.api.botplatform.domain.BotTool}.
 * Solo arma las propuestas. La creación efectiva se hace desde el frontend
 * llamando al POST /api/admin/bot-tools por cada tool aprobada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotToolAiGeneratorService {

    private final BotConnectorRepository connectorRepo;
    private final ConnectorSchemaCacheRepository cacheRepo;
    private final ConnectorTableDescriptionRepository tableDescRepo;
    private final DynamicDataSourceService dataSourceService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL  = "claude-sonnet-4-20250514";
    private static final String FALLBACK_MODEL = "claude-3-5-sonnet-20241022";
    private static final int SAMPLE_ROWS = 3;

    /** Las 7 categorías de tools que Claude puede generar. */
    public enum Category {
        TOP_N,                 // top_destinos, top_vendedores
        COUNT_KPI,             // cantidad de miembros activos, viajes confirmados
        TIME_AGGREGATION,      // facturación por día/semana/mes
        FILTERED_SEARCH,       // buscar miembro por nombre, viajes por destino
        FREQUENT_JOIN,         // miembros + pagos + viaje
        ANOMALY_DETECTION,     // pagos atrasados, viajes sin cupo
        COMPARATIVE            // este mes vs mes pasado
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Genera propuestas de tools para un conector.
     *
     * @param connectorId   ID del conector
     * @param categories    categorías a generar (si vacía, todas)
     * @param model         "sonnet" (default, recomendado) o "haiku" (más barato)
     * @return Map con: { "proposals": [...], "warnings": [...] }
     */
    public Map<String, Object> generate(Long connectorId,
                                        List<Category> categories,
                                        String model) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> proposals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        out.put("proposals", proposals);
        out.put("warnings", warnings);

        if (anthropicKey == null || anthropicKey.isBlank()) {
            warnings.add("API key de Anthropic no configurada (coincidir.anthropic-key)");
            return out;
        }

        BotConnector connector = connectorRepo.findById(connectorId).orElse(null);
        if (connector == null) {
            warnings.add("Connector " + connectorId + " no existe");
            return out;
        }
        ConnectorSchemaCache cache = cacheRepo.findByConnectorId(connectorId).orElse(null);
        if (cache == null || cache.getSchemaJson() == null) {
            warnings.add("El conector no tiene schema cacheado. Hacé 'Escanear esquema' primero.");
            return out;
        }

        if (categories == null || categories.isEmpty()) {
            categories = Arrays.asList(Category.values());
        }

        // 1. Parsear schema
        Map<String, Map<String, Object>> schemaByTable;
        try {
            schemaByTable = parseSchemaByTable(cache.getSchemaJson());
        } catch (Exception e) {
            warnings.add("No pude parsear schemaJson: " + e.getMessage());
            return out;
        }

        // 2. Cargar descripciones por tabla (case-insensitive)
        Map<String, String> descByTable = new HashMap<>();
        try {
            for (ConnectorTableDescription d : tableDescRepo.findByConnectorIdOrderByTableNameAsc(connectorId)) {
                if (d.getTableName() != null && d.getDescription() != null) {
                    descByTable.put(d.getTableName().toLowerCase(Locale.ROOT), d.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("[tool-gen] no pude cargar descripciones de tabla: {}", e.getMessage());
        }

        // 3. Sacar samples de cada tabla del schema (sin filtrar — Claude decide cuáles usar)
        DataSource ds = dataSourceService.getDataSource(connector);
        List<Map<String, Object>> tablesPayload = new ArrayList<>();
        int withSamples = 0, skipped = 0;
        for (Map.Entry<String, Map<String, Object>> e : schemaByTable.entrySet()) {
            String tableName = e.getKey();
            Map<String, Object> schema = e.getValue();
            List<Map<String, Object>> sample;
            try {
                sample = fetchSample(ds, tableName, connector);
                withSamples++;
            } catch (Exception ex) {
                log.info("[tool-gen] sample falló para tabla {}: {}", tableName, ex.getMessage());
                sample = List.of();
                skipped++;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tableName);
            entry.put("description", descByTable.getOrDefault(tableName.toLowerCase(Locale.ROOT), ""));
            entry.put("columns", schema.get("columns"));
            entry.put("foreignKeys", schema.getOrDefault("foreignKeys", List.of()));
            entry.put("sampleRows", sample);
            tablesPayload.add(entry);
        }
        log.info("[tool-gen] tablesPayload preparado: {} tablas, {} con samples, {} sin samples",
                tablesPayload.size(), withSamples, skipped);

        // 4. Llamar a Claude (un solo call: queremos visión holística del schema)
        try {
            String chosenModel = resolveModel(model);
            List<Map<String, Object>> aiProposals = callClaude(connector, tablesPayload, categories, chosenModel);
            for (Map<String, Object> raw : aiProposals) {
                Map<String, Object> validated = validateAndNormalize(raw, connectorId, warnings);
                if (validated != null) {
                    proposals.add(validated);
                }
            }
        } catch (Exception e) {
            log.warn("[tool-gen] llamada a Claude falló", e);
            warnings.add("Falló la generación: " + e.getMessage());
        }

        log.info("[tool-gen] connector={} categorías={} → propuestas={} warnings={}",
                connectorId, categories, proposals.size(), warnings.size());

        out.put("connectorId", connectorId);
        out.put("connectorName", connector.getName());
        out.put("tablesAnalyzed", tablesPayload.size());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveModel(String model) {
        if (model == null) return DEFAULT_MODEL;
        return switch (model.toLowerCase(Locale.ROOT)) {
            case "haiku" -> "claude-haiku-4-5-20251001";
            case "sonnet", "" -> DEFAULT_MODEL;
            default -> model; // permitir pasar un model id exacto
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseSchemaByTable(String schemaJson) throws Exception {
        Map<String, Object> root = jsonMapper.readValue(schemaJson, Map.class);
        List<Map<String, Object>> tables = (List<Map<String, Object>>) root.getOrDefault("tables", List.of());
        Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> t : tables) {
            String name = String.valueOf(t.get("name"));
            if (name != null && !name.isBlank()) {
                byTable.put(name.toLowerCase(Locale.ROOT), t);
            }
        }
        return byTable;
    }

    private List<Map<String, Object>> fetchSample(DataSource ds, String tableName, BotConnector connector) {
        if (tableName == null || !tableName.matches("[a-zA-Z0-9_]+")) {
            return List.of();
        }
        String quoted = quoteIdentifier(tableName, connector);
        String sql = "SELECT * FROM " + quoted + " LIMIT " + SAMPLE_ROWS;
        String type = connector.getDbType() == null ? "MYSQL" : connector.getDbType().name();
        if ("SQLSERVER".equals(type)) {
            sql = "SELECT TOP " + SAMPLE_ROWS + " * FROM " + quoted;
        } else if ("ORACLE".equals(type)) {
            sql = "SELECT * FROM " + quoted + " WHERE ROWNUM <= " + SAMPLE_ROWS;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(8);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        Object v = rs.getObject(i);
                        v = normalizeForJson(v);
                        row.put(meta.getColumnLabel(i), v);
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.debug("[tool-gen] sample {} falló: {}", tableName, e.getMessage());
        }
        return rows;
    }

    /**
     * Convierte valores que Jackson no serializa por default (LocalDateTime,
     * Instant, java.sql.Date, byte[], etc.) a tipos amigables (String, primitivos).
     * El sample es para que Claude vea valores reales — no necesitamos preservar
     * tipos exactos, alcanza con que sea legible.
     */
    private static Object normalizeForJson(Object v) {
        if (v == null) return null;
        // Truncar strings largos
        if (v instanceof String s) {
            return s.length() > 120 ? s.substring(0, 120) + "…" : s;
        }
        // Tipos temporales — todos a su toString ISO
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        if (v instanceof java.util.Date d) return d.toInstant().toString();
        if (v instanceof java.sql.Timestamp t) return t.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toString();
        if (v instanceof java.sql.Time t) return t.toString();
        // byte[]: blob/binary — mostrar tamaño, no contenido
        if (v instanceof byte[] bs) return "<bytes len=" + bs.length + ">";
        // Tipos primitivos, BigDecimal, BigInteger, Boolean, etc. → Jackson los maneja bien
        return v;
    }

    private String quoteIdentifier(String name, BotConnector connector) {
        String type = connector.getDbType() == null ? "MYSQL" : connector.getDbType().name();
        return switch (type) {
            case "MYSQL", "MARIADB" -> "`" + name + "`";
            case "POSTGRES" -> "\"" + name + "\"";
            case "SQLSERVER" -> "[" + name + "]";
            case "ORACLE" -> "\"" + name.toUpperCase(Locale.ROOT) + "\"";
            default -> name;
        };
    }

    /**
     * Llama a Claude con el schema + categorías y le pide proponer tools.
     * Devuelve la lista cruda de propuestas (parseada desde JSON pero sin validar).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callClaude(BotConnector connector,
                                                  List<Map<String, Object>> tablesPayload,
                                                  List<Category> categories,
                                                  String model) throws Exception {
        String prompt = buildPrompt(connector, tablesPayload, categories);

        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8192);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404 && !FALLBACK_MODEL.equals(model)) {
            log.warn("[tool-gen] modelo {} devolvió 404, fallback a {}", model, FALLBACK_MODEL);
            body.put("model", FALLBACK_MODEL);
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                    .build();
            resp = HTTP.send(req2, HttpResponse.BodyHandlers.ofString());
        }
        if (resp.statusCode() / 100 != 2) {
            String b = resp.body();
            String shortB = b == null ? "" : (b.length() > 500 ? b.substring(0, 500) + "..." : b);
            throw new RuntimeException("Anthropic " + resp.statusCode() + ": " + shortB);
        }

        JsonNode root = jsonMapper.readTree(resp.body());
        if ("max_tokens".equals(root.path("stop_reason").asText(""))) {
            log.warn("[tool-gen] respuesta cortada por max_tokens — pueden faltar propuestas");
        }

        StringBuilder text = new StringBuilder();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText(""));
                }
            }
        }
        String raw = text.toString().trim();
        if (raw.startsWith("```")) {
            int firstNl = raw.indexOf('\n');
            if (firstNl >= 0) raw = raw.substring(firstNl + 1);
            int lastFence = raw.lastIndexOf("```");
            if (lastFence >= 0) raw = raw.substring(0, lastFence);
            raw = raw.trim();
        }

        JsonNode parsed = jsonMapper.readTree(raw);
        JsonNode arr = parsed.path("proposals");
        List<Map<String, Object>> proposals = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                Map<String, Object> m = jsonMapper.convertValue(p, Map.class);
                proposals.add(m);
            }
        }
        return proposals;
    }

    private String buildPrompt(BotConnector connector,
                               List<Map<String, Object>> tablesPayload,
                               List<Category> categories) throws Exception {
        StringBuilder p = new StringBuilder();
        p.append("Sos un experto en diseño de tools para asistentes IA conectados a bases de datos.\n\n");
        p.append("Tu tarea: analizar el schema de una base de datos real y proponer un set de tools ");
        p.append("SQL parametrizadas que un asistente operativo (Claude) pueda usar para responder ");
        p.append("preguntas de negocio típicas. Las tools generadas son SELECT (read-only).\n\n");

        // Contexto del negocio
        p.append("=== CONTEXTO DEL NEGOCIO ===\n");
        if (connector.getBusinessGlossary() != null && !connector.getBusinessGlossary().isBlank()) {
            p.append(connector.getBusinessGlossary().trim()).append("\n\n");
        } else {
            p.append("Base de datos \"").append(connector.getName()).append("\". ");
            p.append("Sin glosario configurado — inferí el negocio por los nombres de tablas/columnas.\n\n");
        }

        // DB type para que use sintaxis correcta
        String dbType = connector.getDbType() == null ? "MYSQL" : connector.getDbType().name();
        p.append("=== TIPO DE BASE ===\n").append(dbType).append("\n\n");

        // Categorías
        p.append("=== CATEGORÍAS DE TOOLS A GENERAR ===\n");
        for (Category c : categories) {
            p.append("- ").append(c.name()).append(": ").append(categoryDescription(c)).append("\n");
        }
        p.append("\nPropone 2-5 tools por categoría, dependiendo de cuán rica sea la base para esa categoría. ");
        p.append("NO generes tools redundantes. Si una categoría no tiene sentido en este schema, salteala.\n\n");

        // Schema completo con samples
        p.append("=== SCHEMA + SAMPLES ===\n");
        p.append(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tablesPayload));
        p.append("\n\n");

        // Formato de salida
        p.append("=== INSTRUCCIONES DE SALIDA ===\n");
        p.append("Devolvé SOLO un JSON con este formato exacto (sin markdown, sin texto adicional):\n\n");
        p.append("{\n");
        p.append("  \"proposals\": [\n");
        p.append("    {\n");
        p.append("      \"name\": \"nombre_snake_case\",\n");
        p.append("      \"category\": \"TOP_N\",\n");
        p.append("      \"description\": \"Descripción para Claude: en qué situaciones usarla, qué devuelve. Específica y útil. 1-3 oraciones.\",\n");
        p.append("      \"sqlTemplate\": \"SELECT ... FROM ... WHERE col = :param ORDER BY ... LIMIT :limite\",\n");
        p.append("      \"parametersSchemaJson\": {\n");
        p.append("        \"type\": \"object\",\n");
        p.append("        \"properties\": {\n");
        p.append("          \"param\": {\"type\": \"string\", \"description\": \"Qué es este parámetro\"},\n");
        p.append("          \"limite\": {\"type\": \"integer\", \"description\": \"Cantidad de filas\", \"default\": 10}\n");
        p.append("        },\n");
        p.append("        \"required\": [\"param\"]\n");
        p.append("      },\n");
        p.append("      \"rowLimit\": 100,\n");
        p.append("      \"rationale\": \"Por qué esta tool es útil — 1 oración para que el admin entienda.\"\n");
        p.append("    }\n");
        p.append("  ]\n");
        p.append("}\n\n");

        p.append("REGLAS ESTRICTAS:\n");
        p.append("1. SOLO SELECT statements. NO INSERT/UPDATE/DELETE/DROP/TRUNCATE.\n");
        p.append("2. Usar parámetros nombrados :param (no ? ni $1). Los valores los pone JDBC, NUNCA concatenes strings.\n");
        p.append("3. SI Y SOLO SI un filtro es realmente opcional, usá patrón:\n");
        p.append("     WHERE (:param IS NULL OR columna = :param)\n");
        p.append("   y declaralo en parametersSchemaJson SIN ponerlo en required.\n");
        p.append("4. Nombres de tools en snake_case, español, descriptivos. Ej: 'top_destinos_por_reservas_30d'.\n");
        p.append("5. Sintaxis SQL correcta para ").append(dbType).append(".\n");
        p.append("6. SIEMPRE incluir LIMIT (o equivalente) si la query puede devolver muchas filas.\n");
        p.append("7. Para fechas usá funciones nativas del motor (DATE_SUB, INTERVAL, NOW(), etc. en MySQL).\n");
        p.append("8. Si vas a hacer JOIN, asegurate que las FKs existan en el schema.\n");
        p.append("9. parametersSchemaJson debe ser un JSON Schema válido formato Anthropic tools.\n");
        p.append("10. Si una tabla del schema parece técnica (logs, audits, flyway), evitala.\n");
        return p.toString();
    }

    private String categoryDescription(Category c) {
        return switch (c) {
            case TOP_N -> "Rankings tipo 'top X por Y' (ej: top destinos por reservas, top vendedores por facturación).";
            case COUNT_KPI -> "Conteos / KPIs simples (ej: cantidad de miembros activos, viajes confirmados este mes).";
            case TIME_AGGREGATION -> "Agregaciones temporales (ej: facturación por día/semana/mes, inscripciones por mes).";
            case FILTERED_SEARCH -> "Búsquedas con filtros (ej: buscar miembro por nombre, viajes por destino).";
            case FREQUENT_JOIN -> "Combinaciones útiles entre 2-3 tablas (ej: miembros + sus pagos + su viaje).";
            case ANOMALY_DETECTION -> "Detección de problemas (ej: pagos vencidos, viajes sin cupo lleno, miembros sin contacto).";
            case COMPARATIVE -> "Comparativas temporales (ej: este mes vs mes pasado, este año vs anterior).";
        };
    }

    /**
     * Valida una propuesta cruda de Claude. Devuelve null si la rechaza (con
     * warning agregado) o el Map normalizado y enriquecido si es válida.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> validateAndNormalize(Map<String, Object> raw,
                                                      Long connectorId,
                                                      List<String> warnings) {
        if (raw == null) return null;
        String name = strOrNull(raw.get("name"));
        String description = strOrNull(raw.get("description"));
        String sqlTemplate = strOrNull(raw.get("sqlTemplate"));
        Object schemaObj = raw.get("parametersSchemaJson");
        String category = strOrNull(raw.get("category"));
        String rationale = strOrNull(raw.get("rationale"));
        Object rowLimitObj = raw.get("rowLimit");

        if (name == null || description == null || sqlTemplate == null || schemaObj == null) {
            warnings.add("Propuesta rechazada por campos faltantes: " + (name != null ? name : "?"));
            return null;
        }
        // name: snake_case, sin espacios, máx 100 chars
        if (!name.matches("[a-z][a-z0-9_]{2,99}")) {
            warnings.add("Nombre inválido (debe ser snake_case): " + name);
            return null;
        }
        // sqlTemplate debe empezar con SELECT (case-insensitive, tolera whitespace y CTEs simples)
        String sqlNorm = sqlTemplate.trim().toUpperCase(Locale.ROOT);
        boolean isSelect = sqlNorm.startsWith("SELECT") || sqlNorm.startsWith("WITH");
        if (!isSelect) {
            warnings.add("SQL rechazado (no es SELECT): " + name);
            return null;
        }
        // bloquear keywords destructivas en cualquier parte
        for (String forbidden : new String[]{
                "INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ", "ALTER ", "CREATE ",
                "GRANT ", "REVOKE ", "EXEC ", "EXECUTE "}) {
            if (sqlNorm.contains(forbidden)) {
                warnings.add("SQL rechazado (contiene " + forbidden.trim() + "): " + name);
                return null;
            }
        }

        // schema debe ser un JSON Schema parseable (puede venir como Map o String)
        Map<String, Object> schemaMap;
        try {
            if (schemaObj instanceof Map) {
                schemaMap = (Map<String, Object>) schemaObj;
            } else if (schemaObj instanceof String s) {
                schemaMap = jsonMapper.readValue(s, Map.class);
            } else {
                warnings.add("Schema inválido: " + name);
                return null;
            }
        } catch (Exception e) {
            warnings.add("Schema JSON malformado para " + name + ": " + e.getMessage());
            return null;
        }
        // Sanity check del schema
        Object type = schemaMap.get("type");
        if (!"object".equals(type)) {
            warnings.add("Schema sin type=object: " + name);
            return null;
        }

        // Normalizado
        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("name", name);
        norm.put("category", category != null ? category : "OTHER");
        norm.put("description", description.trim());
        norm.put("sqlTemplate", sqlTemplate.trim());
        norm.put("parametersSchemaJson", schemaMap);
        norm.put("operationType", "QUERY");
        norm.put("connectorId", connectorId);
        norm.put("rowLimit", rowLimitObj instanceof Number ? ((Number) rowLimitObj).intValue() : 100);
        norm.put("active", false);   // Modo borrador: el admin lo activa explícitamente
        norm.put("rationale", rationale != null ? rationale : "");
        return norm;
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
