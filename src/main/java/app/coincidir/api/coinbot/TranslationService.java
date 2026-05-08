package app.coincidir.api.coinbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TranslationService — wrap a Claude API para traducir batches de texto.
 *
 * Reemplaza/centraliza la lógica que antes estaba inline en
 * {@link TranslationController}. El controller sigue funcionando idéntico
 * para back-compat, pero ahora delega acá. Otros componentes (como el
 * servicio de i18n del bot) también la consumen.
 *
 * Política fail-soft: si la API falla o no hay key, devuelve el mapa original
 * sin traducir (en lugar de tirar excepción). El caller decide qué hacer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Traduce un mapa de strings (key → texto en español) al idioma destino.
     * Devuelve un mapa con las mismas keys y los textos traducidos.
     *
     * - Si el target es "es" o vacío → devuelve el mismo mapa (no-op).
     * - Si no hay API key configurada → devuelve los originales (warning en log).
     * - Si la API falla → devuelve los originales (fail-soft, log de warning).
     * - Las entradas con valor blank/null se omiten del request a Claude pero
     *   se conservan en la respuesta como estaban.
     *
     * @param entries  mapa key → texto en español
     * @param targetLang  código del idioma destino (ej: "en", "pt-BR", "it", "fr")
     * @param targetName  nombre legible del idioma para el prompt (ej: "English",
     *                    "Italian"). Si es null/blank, se infiere para los idiomas
     *                    conocidos o se usa el código.
     */
    public Map<String, String> translate(
            Map<String, String> entries,
            String targetLang,
            String targetName
    ) {
        if (entries == null || entries.isEmpty()) return new LinkedHashMap<>();
        Map<String, String> out = new LinkedHashMap<>(entries);

        String target = normalizeLang(targetLang);
        if (target == null || target.isBlank() || "es".equals(target)) {
            return out; // español o desconocido → no-op
        }
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.warn("[TranslationService] sin API key (coincidir.anthropic-key). " +
                     "Devolviendo originales para target={}", target);
            return out;
        }

        // Filtramos entradas vacías
        Map<String, String> toTranslate = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                toTranslate.put(e.getKey(), e.getValue());
            }
        }
        if (toTranslate.isEmpty()) return out;

        String label = (targetName == null || targetName.isBlank())
                ? inferLanguageLabel(target)
                : targetName.trim();

        try {
            Map<String, String> translated = callClaude(toTranslate, label);
            if (translated != null) {
                for (Map.Entry<String, String> e : translated.entrySet()) {
                    String v = e.getValue();
                    if (v != null && !v.isBlank()) {
                        out.put(e.getKey(), v);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TranslationService] error traduciendo a {}: {}", target, e.getMessage());
        }
        return out;
    }

    /** Atajo cuando ya tenés solo el código de idioma. */
    public Map<String, String> translate(Map<String, String> entries, String targetLang) {
        return translate(entries, targetLang, null);
    }

    private Map<String, String> callClaude(Map<String, String> entries, String langLabel) throws Exception {
        String systemPrompt =
            "You are a professional translator for a chatbot/SaaS admin UI. " +
            "You translate short texts (labels, placeholders, welcome messages, button labels) from Spanish to " + langLabel + ". " +
            "CRITICAL rules:\n" +
            "- Preserve ALL markdown syntax: **bold**, *italics*, `code`, lists.\n" +
            "- Preserve ALL emojis exactly where they are.\n" +
            "- Preserve placeholders like {name}, {brand}, %s, $variable — do NOT translate them.\n" +
            "- Preserve proper nouns: brand names, bot names, city names (e.g., 'Lety', 'YES Travel', 'Coincidir' stay as-is).\n" +
            "- Keep the same tone (informal/friendly if original is, formal if formal).\n" +
            "- Do NOT add quotes, explanations, or any extra text.\n" +
            "- For questions in Spanish with ¿...?, use the appropriate target-language punctuation (no ¿ in English/Portuguese/etc).\n" +
            "- If the text is already in the target language, return it unchanged.\n\n" +
            "You will receive a JSON object with keys and Spanish values. Return a JSON object with the SAME keys " +
            "and the values translated to " + langLabel + ". Return ONLY the JSON object, no markdown code fences, no commentary.";

        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", HAIKU_MODEL);
        req.put("max_tokens", 4000);
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
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                .build();

        HttpResponse<String> r = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            log.warn("[TranslationService] claude status={} body={}", r.statusCode(), truncate(r.body(), 300));
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

    /**
     * Mapeo soft de códigos comunes a labels en inglés (para mejorar la calidad
     * del prompt). Para códigos desconocidos, devuelve el código mismo —
     * Claude entiende códigos como "fr", "de", "ja" igual.
     */
    private static String inferLanguageLabel(String code) {
        if (code == null) return "English";
        String c = code.toLowerCase();
        if (c.equals("en") || c.startsWith("en-")) return "English";
        if (c.equals("pt") || c.startsWith("pt-")) return "Brazilian Portuguese";
        if (c.equals("it") || c.startsWith("it-")) return "Italian";
        if (c.equals("fr") || c.startsWith("fr-")) return "French";
        if (c.equals("de") || c.startsWith("de-")) return "German";
        if (c.equals("ja") || c.startsWith("ja-")) return "Japanese";
        if (c.equals("zh") || c.startsWith("zh-")) return "Chinese";
        if (c.equals("ru") || c.startsWith("ru-")) return "Russian";
        if (c.equals("ar") || c.startsWith("ar-")) return "Arabic";
        return code;
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    private static String normalizeLang(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (c.isEmpty()) return null;
        if (c.equalsIgnoreCase("pt") || c.toLowerCase().startsWith("pt-") || c.equalsIgnoreCase("pt-br")) return "pt-BR";
        return c.toLowerCase().startsWith("en") ? "en"
                : c.toLowerCase().startsWith("es") ? "es"
                : c; // permite cualquier código (it, fr, de, etc.)
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
