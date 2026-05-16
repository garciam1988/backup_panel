package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.ConnectorSchemaCacheRepository;
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
 * Genera descripciones en lenguaje natural para tablas de un BotConnector
 * usando IA (Claude).
 *
 * Flujo:
 *   1. Para cada tabla pedida, traer 5 filas de muestra reales con SELECT *.
 *   2. Combinar con el schema (columnas + tipos + FKs) del cache.
 *   3. Mandar todo a Claude en una sola llamada con instrucción de devolver JSON.
 *   4. Parsear el JSON y devolver el mapa tableName → description.
 *
 * Diseño de la llamada a Claude:
 *   - Usamos HAIKU_MODEL — para descripciones cortas no necesitamos Sonnet,
 *     y Haiku es ~3× más rápido y ~5× más barato. La calidad para esto es
 *     más que suficiente.
 *   - Una sola call con todas las tablas en el prompt, no N calls. Reduce
 *     latencia, costos fijos, y tokens repetidos (system prompt + glosario
 *     común). Cap de seguridad: máximo 25 tablas por batch — si vienen
 *     más, el frontend tiene que dividir.
 *   - Pedimos respuesta JSON estructurada {"descriptions":[{"name":..., "description":...}]}.
 *     Más confiable que parsear texto libre.
 *
 * Privacidad:
 *   Las filas de muestra van a Anthropic. Esto NO abre un canal nuevo de
 *   exposición — los datos ya viajan a Anthropic cuando el bot responde
 *   consultas reales. Pero el admin puede excluir tablas sensibles via
 *   whitelist o simplemente no incluirlas en el batch a generar.
 *
 * Fail-soft: si Claude o la BD del cliente fallan, devolvemos lo que se
 * pudo procesar (las descripciones generadas hasta el error). Nunca tiramos
 * excepción — el caller decide qué hacer con lo que recibe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableDescriptionAiService {

    private final BotConnectorRepository connectorRepo;
    private final ConnectorSchemaCacheRepository cacheRepo;
    private final DynamicDataSourceService dataSourceService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final String HAIKU_FALLBACK_MODEL = "claude-3-5-haiku-20241022";
    private static final int MAX_TABLES_PER_BATCH = 5;   // ↓ desde 25: con 25 el response superaba max_tokens y devolvía JSON cortado → parseo fallaba → 0 descripciones
    private static final int SAMPLE_ROWS = 3;            // ↓ desde 5: ídem, prompt más corto

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Genera descripciones para las tablas indicadas.
     *
     * @param connectorId  id del conector
     * @param tableNames   lista de nombres de tabla a procesar (case-insensitive,
     *                     se normaliza a lower). Si está vacía, retorna vacío.
     * @return mapa tableName(lower) → descripción generada por Claude. Solo
     *         contiene entradas exitosas; las que fallaron en algún paso se omiten.
     */
    public Map<String, String> generate(Long connectorId, List<String> tableNames) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tableNames == null || tableNames.isEmpty()) return result;
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.warn("[ai-desc] sin API key (coincidir.anthropic-key)");
            return result;
        }

        BotConnector connector = connectorRepo.findById(connectorId).orElse(null);
        if (connector == null) {
            log.warn("[ai-desc] connector {} no existe", connectorId);
            return result;
        }
        ConnectorSchemaCache cache = cacheRepo.findByConnectorId(connectorId).orElse(null);
        if (cache == null || cache.getSchemaJson() == null) {
            log.warn("[ai-desc] connector {} no tiene schema cacheado. Re-escanealo primero.", connectorId);
            return result;
        }

        // Normalizar nombres (lower, sin vacíos, sin duplicados)
        List<String> normalized = new ArrayList<>();
        for (String t : tableNames) {
            if (t == null) continue;
            String trimmed = t.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !normalized.contains(trimmed)) normalized.add(trimmed);
        }
        if (normalized.isEmpty()) return result;

        // Parsear schema cacheado a estructura accesible
        Map<String, Map<String, Object>> schemaByTable;
        try {
            schemaByTable = parseSchemaByTable(cache.getSchemaJson());
        } catch (Exception e) {
            log.warn("[ai-desc] no pude parsear schemaJson: {}", e.getMessage());
            return result;
        }

        DataSource ds = dataSourceService.getDataSource(connector);

        log.info("[ai-desc] connector={} solicitadas={} batchSize={} samplesPorTabla={}",
                connectorId, normalized.size(), MAX_TABLES_PER_BATCH, SAMPLE_ROWS);

        // Procesar en batches chicos. Si un batch falla, los demás siguen.
        int batchNum = 0;
        for (int i = 0; i < normalized.size(); i += MAX_TABLES_PER_BATCH) {
            batchNum++;
            int end = Math.min(i + MAX_TABLES_PER_BATCH, normalized.size());
            List<String> batch = normalized.subList(i, end);

            List<Map<String, Object>> tablesPayload = new ArrayList<>();
            for (String table : batch) {
                Map<String, Object> schema = schemaByTable.get(table.toLowerCase(Locale.ROOT));
                if (schema == null) {
                    log.info("[ai-desc] tabla '{}' no está en schema cacheado, skip", table);
                    continue;
                }
                List<Map<String, Object>> sample = fetchSample(ds, table, connector);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", table);
                entry.put("columns", schema.get("columns"));
                entry.put("foreignKeys", schema.getOrDefault("foreignKeys", List.of()));
                entry.put("sampleRows", sample);
                tablesPayload.add(entry);
            }
            if (tablesPayload.isEmpty()) {
                log.info("[ai-desc] batch {} sin tablas válidas, skip", batchNum);
                continue;
            }

            try {
                Map<String, String> aiResponses = callClaude(connector, tablesPayload);
                int before = result.size();
                for (Map.Entry<String, String> e : aiResponses.entrySet()) {
                    String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                    String val = e.getValue() == null ? "" : e.getValue().trim();
                    if (!key.isEmpty() && !val.isEmpty()) {
                        result.put(key, val);
                    }
                }
                log.info("[ai-desc] batch {}/{}: tablasEnPayload={} respondidasPorClaude={} acumuladasTotal={}",
                        batchNum, (int) Math.ceil(normalized.size() / (double) MAX_TABLES_PER_BATCH),
                        tablesPayload.size(), aiResponses.size(), result.size() - before + before);
            } catch (Exception e) {
                log.warn("[ai-desc] batch {} falló (sigue con el resto): {}", batchNum, e.getMessage(), e);
            }
        }

        log.info("[ai-desc] terminado. solicitadas={} generadas={}", normalized.size(), result.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseSchemaByTable(String schemaJson) throws Exception {
        Map<String, Object> root = jsonMapper.readValue(schemaJson, Map.class);
        List<Map<String, Object>> tables = (List<Map<String, Object>>) root.getOrDefault("tables", List.of());
        Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> t : tables) {
            String name = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT);
            byTable.put(name, t);
        }
        return byTable;
    }

    /**
     * Trae filas de muestra de una tabla. Hard limit de 5 filas + 10s de timeout.
     *
     * IMPORTANTE — quoting:
     *   No usamos PreparedStatement con parámetro para el nombre de tabla (no
     *   se puede). El nombre lo construimos a partir del schema cacheado, que
     *   a su vez viene de INFORMATION_SCHEMA — fuente confiable, no input
     *   directo del usuario. Igual hacemos un sanity check para evitar
     *   cualquier intento de inyección si por algún motivo el schemaJson se
     *   corrompió: aceptamos solo [a-zA-Z0-9_].
     *
     * Si la tabla está vacía o falla, devolvemos lista vacía — el caller sigue.
     */
    private List<Map<String, Object>> fetchSample(DataSource ds, String tableName, BotConnector connector) {
        if (tableName == null || !tableName.matches("[a-zA-Z0-9_]+")) {
            log.warn("[ai-desc] nombre de tabla rechazado por sanity check: {}", tableName);
            return List.of();
        }
        String quoted = quoteIdentifier(tableName, connector);
        String sql = "SELECT * FROM " + quoted + " LIMIT " + SAMPLE_ROWS;
        // SQL Server no soporta LIMIT — usar TOP en su lugar
        if (connector.getDbType() != null && connector.getDbType().name().equals("SQLSERVER")) {
            sql = "SELECT TOP " + SAMPLE_ROWS + " * FROM " + quoted;
        }
        // Oracle tampoco — usar ROWNUM
        if (connector.getDbType() != null && connector.getDbType().name().equals("ORACLE")) {
            sql = "SELECT * FROM " + quoted + " WHERE ROWNUM <= " + SAMPLE_ROWS;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
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
            log.warn("[ai-desc] no pude traer muestra de '{}': {}", tableName, e.getMessage());
        }
        return rows;
    }

    /**
     * Convierte valores que Jackson no serializa por default (LocalDateTime,
     * Instant, java.sql.Date, byte[], etc.) a tipos amigables (String).
     */
    private static Object normalizeForJson(Object v) {
        if (v == null) return null;
        if (v instanceof String s) {
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        }
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        if (v instanceof java.util.Date d) return d.toInstant().toString();
        if (v instanceof java.sql.Timestamp t) return t.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toString();
        if (v instanceof java.sql.Time t) return t.toString();
        if (v instanceof byte[] bs) return "<bytes len=" + bs.length + ">";
        return v;
    }

    /** Quoting básico para evitar conflictos con palabras reservadas. */
    private String quoteIdentifier(String name, BotConnector connector) {
        String type = connector.getDbType() == null ? "MYSQL" : connector.getDbType().name();
        switch (type) {
            case "MYSQL":
            case "MARIADB":
                return "`" + name + "`";
            case "POSTGRES":
                return "\"" + name + "\"";
            case "SQLSERVER":
                return "[" + name + "]";
            case "ORACLE":
                return "\"" + name.toUpperCase(Locale.ROOT) + "\"";
            default:
                return name;
        }
    }

    /**
     * Arma el prompt con todas las tablas+columnas+muestras y llama a Claude.
     * Espera respuesta JSON estructurada.
     */
    private Map<String, String> callClaude(BotConnector connector,
                                           List<Map<String, Object>> tablesPayload) throws Exception {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Sos un asistente que documenta esquemas de bases de datos.\n\n");
        userPrompt.append("Contexto del negocio: ");
        if (connector.getBusinessGlossary() != null && !connector.getBusinessGlossary().isBlank()) {
            userPrompt.append(connector.getBusinessGlossary().trim()).append("\n\n");
        } else {
            userPrompt.append("Base de datos del cliente \"").append(connector.getName()).append("\".\n\n");
        }
        userPrompt.append("Tu tarea: para cada tabla, escribir UNA descripción de 1-2 oraciones en español ");
        userPrompt.append("rioplatense (Argentina). La descripción debe:\n");
        userPrompt.append("- Explicar qué representa cada FILA de la tabla (ej: \"cada fila es un cliente registrado\").\n");
        userPrompt.append("- Mencionar 1-3 columnas clave o valores no obvios si los hay (ej: \"el campo 'status' puede ser 'A' (activo) o 'C' (cancelado)\").\n");
        userPrompt.append("- NO repetir el nombre de la tabla literal.\n");
        userPrompt.append("- NO usar formato markdown, ni listas, ni saltos de línea — solo texto plano.\n");
        userPrompt.append("- Ser breve (~100 a 200 caracteres por tabla).\n\n");
        userPrompt.append("Tablas a documentar:\n\n");
        userPrompt.append(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tablesPayload));
        userPrompt.append("\n\nRespondé SOLO con un JSON de este formato exacto, sin markdown ni texto adicional:\n");
        userPrompt.append("{\"descriptions\":[{\"name\":\"nombre_tabla\",\"description\":\"descripción aquí\"}]}\n");

        // Intentar con el modelo principal; si tira 404 (modelo deprecado/cambió nombre),
        // reintentar con el fallback. Cualquier otro error se propaga.
        HttpResponse<String> resp;
        try {
            resp = sendAnthropic(HAIKU_MODEL, userPrompt.toString());
            if (resp.statusCode() == 404) {
                log.warn("[ai-desc] modelo {} devolvió 404, fallback a {}", HAIKU_MODEL, HAIKU_FALLBACK_MODEL);
                resp = sendAnthropic(HAIKU_FALLBACK_MODEL, userPrompt.toString());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error de red llamando a Anthropic: " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            String body = resp.body();
            String shortBody = body == null ? "" : (body.length() > 500 ? body.substring(0, 500) + "..." : body);
            log.warn("[ai-desc] Anthropic respondió {} body={}", resp.statusCode(), shortBody);
            throw new RuntimeException("Anthropic " + resp.statusCode() + ": " + shortBody);
        }

        JsonNode root = jsonMapper.readTree(resp.body());

        // Detectar si la respuesta se cortó (stop_reason = max_tokens) — eso suele
        // dejar JSON truncado y rompe el parseo. Lo logueamos para diagnóstico.
        String stopReason = root.path("stop_reason").asText("");
        if ("max_tokens".equals(stopReason)) {
            log.warn("[ai-desc] respuesta cortada por max_tokens — los últimos items pueden venir truncados");
        }

        // Extraer el bloque de texto de la respuesta
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

        // Limpiar fences de markdown si Claude las puso pese a la instrucción
        if (raw.startsWith("```")) {
            int firstNl = raw.indexOf('\n');
            if (firstNl >= 0) raw = raw.substring(firstNl + 1);
            int lastFence = raw.lastIndexOf("```");
            if (lastFence >= 0) raw = raw.substring(0, lastFence);
            raw = raw.trim();
        }

        // Parsear el JSON. Si está cortado, intentamos rescatar las descripciones
        // completas que ya están en el array.
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode parsed = jsonMapper.readTree(raw);
            JsonNode descs = parsed.path("descriptions");
            if (descs.isArray()) {
                for (JsonNode item : descs) {
                    String name = item.path("name").asText("").trim();
                    String desc = item.path("description").asText("").trim();
                    if (!name.isEmpty() && !desc.isEmpty()) {
                        out.put(name, desc);
                    }
                }
            }
        } catch (Exception parseErr) {
            // JSON corrupto. Mostramos el preview del raw y intentamos rescatar
            // los objetos completos por regex (parser-fallback).
            String preview = raw.length() > 400 ? raw.substring(0, 400) + "..." : raw;
            log.warn("[ai-desc] no pude parsear JSON, intentando rescate. preview={}", preview);
            out = rescueDescriptionsFromTruncatedJson(raw);
        }
        return out;
    }

    /** Envía un request a Anthropic con el modelo dado. Comparte timeouts/headers. */
    private HttpResponse<String> sendAnthropic(String model, String userPromptText) throws Exception {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8192);   // antes 4096; con batches chicos sigue holgado
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPromptText);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Fallback parser: cuando el JSON viene cortado por max_tokens, lo recorremos
     * a mano buscando objetos {"name":"...","description":"..."} completos.
     * Devuelve un Map con todos los que matcheen.
     */
    private Map<String, String> rescueDescriptionsFromTruncatedJson(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        // Regex tolerante: name primero, description después, con espacios y escapes simples.
        // No cubre todos los escapes JSON pero alcanza para descripciones razonables.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");
        java.util.regex.Matcher m = p.matcher(raw);
        while (m.find()) {
            String name = m.group(1).trim().toLowerCase(Locale.ROOT);
            String desc = m.group(2).trim().replace("\\\"", "\"");
            if (!name.isEmpty() && !desc.isEmpty()) {
                out.put(name, desc);
            }
        }
        if (!out.isEmpty()) {
            log.info("[ai-desc] rescue parser recuperó {} descripciones del JSON truncado", out.size());
        }
        return out;
    }
}
