package app.coincidir.api.botplatform.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BotToolSchemaAiController
 *
 * Endpoint que usa Claude Haiku para generar el JSON Schema de parámetros
 * (formato Anthropic input_schema) a partir del SQL template y la descripción
 * de la tool.
 *
 * Si falla la IA (sin API key, timeout, 5xx), devuelve un schema "regex-only"
 * como fallback en vez de romper — el frontend siempre recibe algo válido.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tools/ai")
@RequiredArgsConstructor
public class BotToolSchemaAiController {

    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final Pattern SQL_PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)\\b");

    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/generate-schema")
    public Map<String, Object> generateSchema(@RequestBody GenerateSchemaRequest body) {
        String sql = body.sqlTemplate == null ? "" : body.sqlTemplate;
        String desc = body.description == null ? "" : body.description;

        // Extraemos parámetros con regex (siempre, lo usamos para validar la
        // respuesta de la IA y como fallback si la IA falla).
        List<String> params = extractParams(sql);

        Map<String, Object> out = new HashMap<>();

        if (params.isEmpty()) {
            out.put("schema", emptySchema());
            out.put("source", "regex");
            return out;
        }

        // Sin API key configurada → fallback regex
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.info("[SchemaAI] Sin anthropic-key, devolviendo schema regex");
            out.put("schema", regexSchema(params));
            out.put("source", "regex");
            return out;
        }

        try {
            JsonNode aiSchema = callClaudeForSchema(sql, desc, params);
            if (aiSchema != null && validateSchema(aiSchema, params)) {
                out.put("schema", aiSchema);
                out.put("source", "ai");
                return out;
            }
            log.warn("[SchemaAI] Respuesta IA inválida, usando fallback regex");
        } catch (Exception e) {
            log.warn("[SchemaAI] Error llamando a Claude: {}", e.getMessage());
        }

        out.put("schema", regexSchema(params));
        out.put("source", "regex");
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Llamada a Claude
    // ─────────────────────────────────────────────────────────────────────
    private JsonNode callClaudeForSchema(String sql, String desc, List<String> params) throws Exception {
        String systemPrompt = buildSystemPrompt();
        String userMessage = buildUserMessage(sql, desc, params);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", 800);
        requestBody.put("temperature", 0.0);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", userMessage);
        messages.add(msg);
        requestBody.set("messages", messages);
        requestBody.put("system", systemPrompt);

        String payload = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("[SchemaAI] Anthropic status={} body={}", response.statusCode(), truncate(response.body(), 300));
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) return null;
        String text = content.get(0).path("text").asText(null);
        if (text == null) return null;

        // Claude a veces devuelve el JSON envuelto en prosa. Extraemos entre el
        // primer '{' y el último '}' como haces en otros servicios del proyecto.
        String json = extractJsonObject(text);
        if (json == null) return null;
        return objectMapper.readTree(json);
    }

    private static String buildSystemPrompt() {
        return "Sos un asistente técnico que genera JSON Schemas para herramientas (tools) " +
                "de Claude siguiendo el formato Anthropic input_schema.\n\n" +
                "Recibís una consulta SQL con parámetros con prefijo ':' y la descripción de " +
                "la tool. Tu trabajo es devolver UN ÚNICO JSON Schema válido, SIN texto extra, " +
                "SIN markdown, SIN bloques ```.\n\n" +
                "Reglas:\n" +
                "- El schema SIEMPRE tiene \"type\": \"object\", \"properties\": {...} y \"required\": [...].\n" +
                "- Las propiedades deben tener EXACTAMENTE los mismos nombres que los parámetros :x del SQL.\n" +
                "- Inferí el \"type\" de cada parámetro mirando el contexto del SQL:\n" +
                "    · columnas .id, _id, _nro → \"integer\"\n" +
                "    · columnas con fecha, date, _at → \"string\" con \"format\": \"date\" (o date-time si tiene tiempo)\n" +
                "    · cantidad, stock, precio, total → \"number\"\n" +
                "    · activo, is_, _flag → \"boolean\"\n" +
                "    · cualquier otro → \"string\"\n" +
                "- Escribí una \"description\" corta (1 oración, en español) útil para que el modelo sepa cuándo " +
                "  usar ese parámetro. Mencioná a qué campo/tabla corresponde si queda claro del SQL. " +
                "  Si la descripción de la tool da ejemplos de valores, incluílos (ej: \"ej: 3, 15, 42\").\n" +
                "- Todos los parámetros que aparecen en el SQL van en \"required\", salvo que claramente sean opcionales " +
                "  (ej. aparecen solo dentro de COALESCE o NULLIF). Si dudás, metelos todos en required.\n" +
                "- NO agregues properties que no están en el SQL. NO omitas ninguna que sí está.\n\n" +
                "Formato de respuesta (EJEMPLO, no copiar literal):\n" +
                "{\"type\":\"object\",\"properties\":{\"nro_operacion\":{\"type\":\"integer\",\"description\":\"Número de operación del cliente. Es el travel_group.id.\"}},\"required\":[\"nro_operacion\"]}";
    }

    private static String buildUserMessage(String sql, String desc, List<String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("Descripción de la tool:\n");
        sb.append(desc.isBlank() ? "(sin descripción)" : desc).append("\n\n");
        sb.append("SQL template:\n").append(sql).append("\n\n");
        sb.append("Parámetros detectados en el SQL: ").append(params).append("\n\n");
        sb.append("Devolvé SOLO el JSON del input_schema, sin texto adicional.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Schema fallback (regex-only) si la IA no está disponible
    // ─────────────────────────────────────────────────────────────────────
    private ObjectNode regexSchema(List<String> params) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        for (String p : params) {
            ObjectNode prop = objectMapper.createObjectNode();
            prop.put("type", inferTypeFromName(p));
            prop.put("description", "");
            props.set(p, prop);
            required.add(p);
        }
        schema.set("properties", props);
        schema.set("required", required);
        return schema;
    }

    private ObjectNode emptySchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        schema.set("required", objectMapper.createArrayNode());
        return schema;
    }

    // Heurística simple de tipo por nombre (solo para fallback).
    private static String inferTypeFromName(String name) {
        String n = name.toLowerCase();
        if (n.endsWith("_id") || n.equals("id") || n.startsWith("id_") || n.contains("nro") || n.contains("numero")) return "integer";
        if (n.startsWith("is_") || n.startsWith("es_") || n.endsWith("_activo") || n.endsWith("_flag")) return "boolean";
        if (n.contains("fecha") || n.contains("date") || n.endsWith("_at")) return "string";
        if (n.contains("precio") || n.contains("stock") || n.contains("cantidad") || n.contains("total") || n.contains("monto")) return "number";
        return "string";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Validación: el schema devuelto debe tener exactamente los mismos
    // params que detectó el regex (defensa contra alucinaciones).
    // ─────────────────────────────────────────────────────────────────────
    private static boolean validateSchema(JsonNode schema, List<String> expectedParams) {
        if (!"object".equals(schema.path("type").asText(null))) return false;
        JsonNode props = schema.path("properties");
        if (!props.isObject()) return false;
        LinkedHashSet<String> have = new LinkedHashSet<>();
        props.fieldNames().forEachRemaining(have::add);
        if (have.size() != expectedParams.size()) return false;
        for (String p : expectedParams) if (!have.contains(p)) return false;
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private static List<String> extractParams(String sql) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (sql == null) return out;
        Matcher m = SQL_PARAM.matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            if (seen.add(name)) out.add(name);
        }
        return out;
    }

    private static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GenerateSchemaRequest {
        public String sqlTemplate;
        public String description;
    }
}
