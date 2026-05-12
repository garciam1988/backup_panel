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
    private static final int MAX_TABLES_PER_BATCH = 25;
    private static final int SAMPLE_ROWS = 5;

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

        // Normalizar nombres
        List<String> normalized = new ArrayList<>();
        for (String t : tableNames) {
            if (t == null) continue;
            String trimmed = t.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        if (normalized.isEmpty()) return result;
        if (normalized.size() > MAX_TABLES_PER_BATCH) {
            log.warn("[ai-desc] batch excede {} tablas — truncando", MAX_TABLES_PER_BATCH);
            normalized = normalized.subList(0, MAX_TABLES_PER_BATCH);
        }

        // Parsear schema cacheado a estructura accesible
        Map<String, Map<String, Object>> schemaByTable;
        try {
            schemaByTable = parseSchemaByTable(cache.getSchemaJson());
        } catch (Exception e) {
            log.warn("[ai-desc] no pude parsear schemaJson: {}", e.getMessage());
            return result;
        }

        // Traer muestras desde la BD del cliente
        DataSource ds = dataSourceService.getDataSource(connector);
        List<Map<String, Object>> tablesPayload = new ArrayList<>();
        for (String table : normalized) {
            Map<String, Object> schema = schemaByTable.get(table.toLowerCase(Locale.ROOT));
            if (schema == null) {
                log.info("[ai-desc] tabla {} no está en schema cacheado, skip", table);
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
        if (tablesPayload.isEmpty()) return result;

        // Llamar a Claude
        try {
            Map<String, String> aiResponses = callClaude(connector, tablesPayload);
            // Normalizar las keys de Claude a lower también (por si las devuelve con casing distinto)
            for (Map.Entry<String, String> e : aiResponses.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                String val = e.getValue() == null ? "" : e.getValue().trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    result.put(key, val);
                }
            }
        } catch (Exception e) {
            log.warn("[ai-desc] llamada a Claude falló: {}", e.getMessage());
            // result puede estar parcialmente vacío — está bien
        }
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
                        // Truncar valores muy largos para no inflar el prompt
                        if (v instanceof String s && s.length() > 200) {
                            v = s.substring(0, 200) + "…";
                        }
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

        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", HAIKU_MODEL);
        body.put("max_tokens", 4096);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt.toString());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Anthropic " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = jsonMapper.readTree(resp.body());

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

        // Parsear el JSON
        JsonNode parsed = jsonMapper.readTree(raw);
        JsonNode descs = parsed.path("descriptions");
        Map<String, String> out = new LinkedHashMap<>();
        if (descs.isArray()) {
            for (JsonNode item : descs) {
                String name = item.path("name").asText("").trim();
                String desc = item.path("description").asText("").trim();
                if (!name.isEmpty() && !desc.isEmpty()) {
                    out.put(name, desc);
                }
            }
        }
        return out;
    }
}
