package app.coincidir.api.coinbot;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * TranslationController — traduce batches de strings del admin al cambiar idioma.
 *
 * POST /api/admin/translate
 *   body: { "targetLanguage": "en" | "pt-BR" | "es", "entries": { "key1": "texto 1", "key2": "texto 2", ... } }
 *   resp: { "entries": { "key1": "translation 1", ... } }
 *
 * El contrato es por-clave: el frontend pasa los valores que quiere traducir
 * con sus keys y recibe el mismo objeto con los valores traducidos. Si Claude
 * falla o no hay API key, devuelve los originales sin modificar (fail-soft).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/translate")
@RequiredArgsConstructor
public class TranslationController {

    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @PostMapping
    public TranslateResponse translate(@RequestBody TranslateRequest body) {
        if (body == null || body.entries == null || body.entries.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta 'entries'");

        String target = normalizeLang(body.targetLanguage);
        TranslateResponse out = new TranslateResponse();
        out.entries = new LinkedHashMap<>(body.entries);

        // Si es español o no hay API key, devolvemos igual (fail-soft)
        if ("es".equals(target)) {
            log.info("[Translate] target=es → no-op");
            return out;
        }
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.warn("[Translate] sin API key, devolviendo originales");
            return out;
        }

        // Filtramos entradas vacías — no tiene sentido gastar tokens en ellas
        Map<String, String> toTranslate = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entries.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                toTranslate.put(e.getKey(), e.getValue());
            }
        }
        if (toTranslate.isEmpty()) return out;

        try {
            Map<String, String> translated = callClaude(toTranslate, target);
            if (translated != null) {
                for (Map.Entry<String, String> e : translated.entrySet()) {
                    // Solo pisamos si la traducción es válida (no vacía, distinta)
                    String v = e.getValue();
                    if (v != null && !v.isBlank()) {
                        out.entries.put(e.getKey(), v);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Translate] error: {}", e.getMessage());
            // Fail-soft: devolvemos originales
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    private Map<String, String> callClaude(Map<String, String> entries, String target) throws Exception {
        String langLabel = "en".equals(target) ? "English" : ("pt-BR".equals(target) ? "Brazilian Portuguese" : target);

        String systemPrompt =
            "You are a professional translator for a chatbot/SaaS admin UI. " +
            "You translate short texts (labels, placeholders, welcome messages) from Spanish to " + langLabel + ". " +
            "CRITICAL rules:\n" +
            "- Preserve ALL markdown syntax: **bold**, *italics*, `code`, lists.\n" +
            "- Preserve ALL emojis exactly where they are.\n" +
            "- Preserve placeholders like {name}, {brand}, %s, $variable — do NOT translate them.\n" +
            "- Preserve proper nouns: brand names, bot names, city names (e.g., 'Lety', 'YES Travel', 'Coincidir' stay as-is).\n" +
            "- Keep the same tone (informal/friendly if original is, formal if formal).\n" +
            "- Do NOT add quotes, explanations, or any extra text.\n" +
            "- For questions in Spanish with ¿...?, use the appropriate target-language punctuation (no ¿ in English/Portuguese).\n" +
            "- If the text is already in the target language, return it unchanged.\n\n" +
            "You will receive a JSON object with keys and Spanish values. Return a JSON object with the SAME keys " +
            "and the values translated to " + langLabel + ". Return ONLY the JSON object, no markdown code fences, no commentary.";

        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", HAIKU_MODEL);
        req.put("max_tokens", 2000);
        req.put("temperature", 0.2);
        req.put("system", systemPrompt);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", "Translate this JSON to " + langLabel + ":\n\n" + objectMapper.writeValueAsString(entries));
        messages.add(msg);
        req.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                .build();

        HttpResponse<String> r = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            log.warn("[Translate] claude status={} body={}", r.statusCode(), truncate(r.body(), 300));
            return null;
        }

        JsonNode root = objectMapper.readTree(r.body());
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) return null;
        String text = content.get(0).path("text").asText("").trim();
        String json = extractJson(text);
        if (json == null) return null;
        return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    private static String normalizeLang(String code) {
        if (code == null) return "es";
        String c = code.trim();
        if (c.equalsIgnoreCase("pt") || c.toLowerCase().startsWith("pt-") || c.equalsIgnoreCase("pt-br")) return "pt-BR";
        if (c.toLowerCase().startsWith("en")) return "en";
        return "es";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─── DTOs ────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateRequest {
        public String targetLanguage;
        public Map<String, String> entries;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateResponse {
        public Map<String, String> entries;
    }
}
