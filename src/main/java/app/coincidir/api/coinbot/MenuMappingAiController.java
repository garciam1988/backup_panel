package app.coincidir.api.coinbot;

import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * MenuMappingAiController — endpoint /admin que usa Claude Haiku para inferir
 * el mapeo de columnas del Excel → roles del menú digital (name, description,
 * price, category, tags, featured, imageName).
 *
 * Toma las primeras 3 filas del catálogo como muestra, le pasa la estructura
 * a Claude y recibe un JSON con el mapping inferido. Si la IA falla, cae a
 * un fallback heurístico por keywords (precio/precio venta, nombre/producto,
 * etc).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/menu/ai")
@RequiredArgsConstructor
public class MenuMappingAiController {

    private final ExcelCatalogService catalogService;
    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // Roles que Claude puede asignar a columnas del Excel
    private static final List<String> ROLES = List.of(
            "name", "description", "price", "category", "tags", "featured", "imageName"
    );

    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/detect-columns")
    @Transactional(readOnly = true)
    public Map<String, Object> detectColumns(@RequestBody DetectRequest body) {
        if (body == null || body.catalogId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta catalogId");

        // Tomamos las primeras 3 filas como muestra (suficiente para que Claude
        // entienda el tipo de datos de cada columna sin gastar tokens).
        List<ExcelCatalogRow> rows = catalogService.preview(body.catalogId, null, 3);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El catálogo está vacío");

        // Extraer columnas de la primera fila
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> sampleRows = new ArrayList<>();
        try {
            for (ExcelCatalogRow r : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(r.getDataJson(), Map.class);
                if (columns.isEmpty()) columns.addAll(data.keySet());
                sampleRows.add(data);
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error parseando filas: " + e.getMessage());
        }

        Map<String, Object> out = new HashMap<>();
        out.put("columns", columns);

        // Sin API key → fallback heurístico
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.info("[MenuAI] Sin anthropic-key, fallback heurístico");
            out.put("mapping", heuristicMapping(columns));
            out.put("source", "heuristic");
            return out;
        }

        try {
            Map<String, String> aiMapping = callClaude(columns, sampleRows);
            if (aiMapping != null && validateMapping(aiMapping, columns)) {
                out.put("mapping", aiMapping);
                out.put("source", "ai");
                return out;
            }
            log.warn("[MenuAI] Respuesta IA inválida, fallback heurístico");
        } catch (Exception e) {
            log.warn("[MenuAI] Error llamando a Claude: {}", e.getMessage());
        }
        out.put("mapping", heuristicMapping(columns));
        out.put("source", "heuristic");
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Llamada a Claude Haiku
    // ─────────────────────────────────────────────────────────────────────
    private Map<String, String> callClaude(List<String> columns, List<Map<String, Object>> sampleRows) throws Exception {
        String systemPrompt = buildSystemPrompt();
        String userMessage = buildUserMessage(columns, sampleRows);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", 500);
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
            log.warn("[MenuAI] Anthropic status={} body={}", response.statusCode(), truncate(response.body(), 300));
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) return null;
        String text = content.get(0).path("text").asText(null);
        if (text == null) return null;

        String json = extractJsonObject(text);
        if (json == null) return null;
        return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    }

    private static String buildSystemPrompt() {
        return "Sos un asistente que mapea columnas de un Excel a roles de un menú digital. " +
                "Recibís una lista de columnas del Excel y ejemplos de filas. Debés devolver un " +
                "JSON con este formato EXACTO (sin markdown, sin ``` ni texto adicional):\n\n" +
                "{\n" +
                "  \"name\": \"nombre_columna_del_excel\",\n" +
                "  \"description\": \"...\" o \"\" si no hay,\n" +
                "  \"price\": \"...\" o \"\",\n" +
                "  \"category\": \"...\" o \"\",\n" +
                "  \"tags\": \"...\" o \"\",\n" +
                "  \"featured\": \"...\" o \"\",\n" +
                "  \"imageName\": \"...\" o \"\"\n" +
                "}\n\n" +
                "Reglas:\n" +
                "- 'name' (OBLIGATORIO): la columna del producto/servicio/item. Ejemplos: 'Producto', 'Nombre', 'Item', 'Descripción del item'.\n" +
                "- 'price': la columna de precio de venta al cliente (no costo, no margen). Ej: 'Precio Venta', 'Precio', 'PVP', 'Precio final'.\n" +
                "- 'description': texto largo descriptivo si existe. Ej: 'Descripción', 'Detalle'.\n" +
                "- 'category': agrupador. Ej: 'Categoría', 'Rubro', 'Sección', 'Tipo'.\n" +
                "- 'tags': etiquetas separadas por coma. Ej: 'Tags', 'Etiquetas', 'Atributos'.\n" +
                "- 'featured': flag booleano de destacado. Ej: 'Destacado', 'Featured', 'Promo'.\n" +
                "- 'imageName': nombre de archivo de imagen. Ej: 'Imagen', 'Foto', 'Image'.\n" +
                "- Si una columna NO existe para un rol, devolvé string vacío.\n" +
                "- No inventes columnas que no estén en la lista. Usá EXACTAMENTE el nombre que aparece.\n" +
                "- Ignorá columnas técnicas como 'ID', 'Stock', 'Activo', 'Margen', 'Costo'.\n";
    }

    private static String buildUserMessage(List<String> columns, List<Map<String, Object>> sampleRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Columnas del Excel: ").append(columns).append("\n\n");
        sb.append("Ejemplos de filas (primeras ").append(sampleRows.size()).append("):\n");
        for (int i = 0; i < sampleRows.size(); i++) {
            sb.append(i + 1).append(". ").append(sampleRows.get(i)).append("\n");
        }
        sb.append("\nDevolvé SOLO el JSON del mapping, sin texto adicional.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fallback heurístico (si no hay API key o Claude falla)
    // ─────────────────────────────────────────────────────────────────────
    private static Map<String, String> heuristicMapping(List<String> columns) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String role : ROLES) out.put(role, "");

        // Patrones (case-insensitive, sin acentos)
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        patterns.put("name",        List.of("producto", "nombre", "item", "articulo", "descripcion del item"));
        patterns.put("description", List.of("descripcion", "detalle", "resumen"));
        patterns.put("price",       List.of("precio venta", "precio final", "pvp", "precio", "valor"));
        patterns.put("category",    List.of("categoria", "rubro", "seccion", "tipo", "grupo"));
        patterns.put("tags",        List.of("tags", "etiquetas", "atributos"));
        patterns.put("featured",    List.of("destacado", "featured", "promo", "estrella"));
        patterns.put("imageName",   List.of("imagen", "foto", "image", "picture"));

        for (Map.Entry<String, List<String>> e : patterns.entrySet()) {
            String bestMatch = null;
            for (String pat : e.getValue()) {
                for (String col : columns) {
                    if (norm(col).equals(norm(pat))) { bestMatch = col; break; }
                }
                if (bestMatch != null) break;
            }
            // Si no hubo exacto, probamos contains
            if (bestMatch == null) {
                for (String pat : e.getValue()) {
                    for (String col : columns) {
                        if (norm(col).contains(norm(pat))) { bestMatch = col; break; }
                    }
                    if (bestMatch != null) break;
                }
            }
            if (bestMatch != null) out.put(e.getKey(), bestMatch);
        }
        return out;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .trim();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Validación: los valores del mapping deben ser columnas existentes
    // (defensa contra alucinaciones de Claude).
    // ─────────────────────────────────────────────────────────────────────
    private static boolean validateMapping(Map<String, String> mapping, List<String> columns) {
        if (mapping == null || mapping.isEmpty()) return false;
        Set<String> validCols = new HashSet<>(columns);
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String val = e.getValue();
            if (val == null || val.isEmpty()) continue;
            if (!validCols.contains(val)) return false;
        }
        // Al menos 'name' debe estar mapeado
        String name = mapping.get("name");
        return name != null && !name.isEmpty();
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
    public static class DetectRequest {
        public Long catalogId;
    }
}
