package app.coincidir.api.botplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenApiImporter — fetch + parse de un documento OpenAPI 3.x (Swagger 2 no soportado).
 *
 * Input: URL del openapi.json (ej: https://api.mi-sistema.com/v3/api-docs).
 * Output: lista de EndpointSpec listos para guardar como ApiEndpoint.
 *
 * Qué hace:
 *   1. Descarga el JSON (con timeout).
 *   2. Recorre "paths" + "methods".
 *   3. Por cada operation, arma:
 *      - toolName: snake_case del operationId, o auto-derivado del path+method.
 *      - description: summary/description del endpoint.
 *      - inputSchema: combina path params + query params + requestBody schema.
 *   4. Resuelve $ref hacia components/schemas.
 */
@Slf4j
@Service
public class OpenApiImporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static class EndpointSpec {
        public String method;       // GET, POST, etc (upper)
        public String path;         // /users/{id}
        public String toolName;     // search_users
        public String description;  // texto para Claude
        public String tag;          // primer tag del operation
        public String operationId;  // si vino
        public String inputSchemaJson;  // JSON Schema completo

        // Si el endpoint es probablemente seguro para leer (GET/HEAD)
        public boolean isReadOnly() {
            return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        }
    }

    /** Descarga y parsea un openapi.json. Lista vacía si falla. */
    public List<EndpointSpec> fetchAndParse(String openapiUrl) {
        if (openapiUrl == null || openapiUrl.isBlank()) return List.of();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(openapiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Coincidir-Bot/1.0")
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[OpenAPI] status {} fetching {}", res.statusCode(), openapiUrl);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(res.body());
            return parse(root);
        } catch (Exception e) {
            log.warn("[OpenAPI] error fetching/parsing: {}", e.getMessage());
            return List.of();
        }
    }

    /** Parsea un JsonNode ya descargado (útil para tests). */
    public List<EndpointSpec> parse(JsonNode root) {
        List<EndpointSpec> out = new ArrayList<>();

        // Validación mínima: debe tener "paths" y (opcionalmente) openapi version 3+
        if (root == null || !root.has("paths")) {
            log.warn("[OpenAPI] documento sin 'paths', no parece ser un OpenAPI válido");
            return out;
        }
        // Soportamos OpenAPI 3.x. Swagger 2 (swagger: "2.0") tiene estructura distinta.
        String openapiVersion = root.path("openapi").asText("");
        if (openapiVersion.isBlank() && root.has("swagger")) {
            log.warn("[OpenAPI] documento es Swagger 2.0, no soportado. Se necesita OpenAPI 3.x.");
            return out;
        }

        JsonNode components = root.path("components");
        JsonNode schemas = components.path("schemas");

        JsonNode paths = root.get("paths");
        Iterator<Map.Entry<String, JsonNode>> pathsIter = paths.fields();
        while (pathsIter.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathsIter.next();
            String path = pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();

            for (String method : new String[]{"get", "post", "put", "patch", "delete"}) {
                if (!pathNode.has(method)) continue;
                JsonNode op = pathNode.get(method);
                try {
                    EndpointSpec spec = buildSpec(method.toUpperCase(Locale.ROOT), path, op, pathNode, schemas);
                    if (spec != null) out.add(spec);
                } catch (Exception e) {
                    log.warn("[OpenAPI] error en {} {}: {}", method, path, e.getMessage());
                }
            }
        }
        log.info("[OpenAPI] parseados {} endpoints", out.size());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    private EndpointSpec buildSpec(String method, String path, JsonNode op, JsonNode pathLevel, JsonNode schemas) {
        EndpointSpec spec = new EndpointSpec();
        spec.method = method;
        spec.path = path;
        spec.operationId = op.path("operationId").asText("");

        // Nombre de tool
        String name = spec.operationId;
        if (name == null || name.isBlank()) name = autoToolName(method, path);
        spec.toolName = toSnakeCase(name);

        // Descripción
        String summary = op.path("summary").asText("");
        String desc = op.path("description").asText("");
        if (!summary.isBlank() && !desc.isBlank()) spec.description = summary + " — " + desc;
        else if (!summary.isBlank()) spec.description = summary;
        else if (!desc.isBlank()) spec.description = desc;
        else spec.description = method + " " + path;

        // Limitar description para que no explote el prompt
        if (spec.description.length() > 500) spec.description = spec.description.substring(0, 497) + "...";

        // Tag (para agrupar en UI)
        JsonNode tags = op.path("tags");
        if (tags.isArray() && !tags.isEmpty()) spec.tag = tags.get(0).asText("");

        // Input schema: combina parameters (path + query + header) + requestBody
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        // Parameters: pueden estar al nivel del path o de la operation
        collectParameters(pathLevel.path("parameters"), properties, required, schemas);
        collectParameters(op.path("parameters"), properties, required, schemas);

        // RequestBody
        JsonNode body = op.path("requestBody");
        if (body.isObject()) {
            JsonNode content = body.path("content");
            JsonNode jsonContent = content.path("application/json");
            if (jsonContent.isObject()) {
                JsonNode bodySchema = resolveRef(jsonContent.path("schema"), schemas);
                if (bodySchema.isObject()) {
                    properties.set("body", bodySchema);
                    if (body.path("required").asBoolean(false)) required.add("body");
                }
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) schema.set("required", required);
        try {
            spec.inputSchemaJson = objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            spec.inputSchemaJson = "{}";
        }

        return spec;
    }

    private void collectParameters(JsonNode params, ObjectNode properties, ArrayNode required, JsonNode schemas) {
        if (!params.isArray()) return;
        for (JsonNode p : params) {
            JsonNode param = resolveRef(p, schemas);
            String pname = param.path("name").asText("");
            if (pname.isBlank()) continue;
            // "in" puede ser path, query, header, cookie. Excluimos header/cookie.
            String pin = param.path("in").asText("query");
            if ("header".equals(pin) || "cookie".equals(pin)) continue;

            JsonNode pschema = resolveRef(param.path("schema"), schemas);
            ObjectNode propDef = objectMapper.createObjectNode();
            if (pschema.has("type")) propDef.set("type", pschema.get("type"));
            else propDef.put("type", "string");
            if (pschema.has("enum")) propDef.set("enum", pschema.get("enum"));
            if (pschema.has("format")) propDef.set("format", pschema.get("format"));

            String pdesc = param.path("description").asText("");
            if (!pdesc.isBlank()) propDef.put("description", pdesc);

            properties.set(pname, propDef);
            if (param.path("required").asBoolean(false)) required.add(pname);
        }
    }

    /** Resuelve $ref a components/schemas si corresponde; sino devuelve el nodo tal cual. */
    private JsonNode resolveRef(JsonNode node, JsonNode schemas) {
        if (node == null || !node.isObject()) return node;
        String ref = node.path("$ref").asText("");
        if (ref.startsWith("#/components/schemas/")) {
            String name = ref.substring("#/components/schemas/".length());
            JsonNode resolved = schemas.path(name);
            if (resolved.isObject()) return resolved;
        }
        return node;
    }

    /** "getUserById" → "get_user_by_id" ; "/users/{id}" GET → "get_users_id" */
    private static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return "unnamed";
        // Primero reemplazo no alfanuméricos por _
        s = s.replaceAll("[^A-Za-z0-9]+", "_");
        // Inserta _ antes de mayúsculas
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        s = s.toLowerCase(Locale.ROOT).replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "unnamed" : s;
    }

    private static String autoToolName(String method, String path) {
        // GET /users/{id}/orders → get_users_id_orders
        String clean = path.replaceAll("[{}]", "").replaceAll("[^A-Za-z0-9]+", "_");
        return method.toLowerCase(Locale.ROOT) + "_" + clean;
    }
}
