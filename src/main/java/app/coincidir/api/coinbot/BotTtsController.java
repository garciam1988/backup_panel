package app.coincidir.api.coinbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BotTtsController — Proxies de texto-a-voz (TTS) para que el frontend
 * pueda generar audio SIN exponer las API keys de ElevenLabs ni OpenAI.
 *
 * Mismo patrón que BotAiController: el backend tiene las keys, el frontend
 * solo manda texto + parámetros conocidos. Devuelve el audio binario (mp3)
 * tal como vuelve del proveedor para que el frontend lo use como blob URL.
 *
 * Endpoints:
 *   - POST /api/coinbot/ai/tts/elevenlabs  — síntesis con ElevenLabs (prioritario)
 *   - POST /api/coinbot/ai/tts/openai       — síntesis con OpenAI (fallback)
 *
 * Defensas anti-abuso (mismas que BotAiController):
 *   1. Rate limit por IP (compartido con BotAiController vía clave separada).
 *   2. Payload size limit (5KB para texto a sintetizar).
 *   3. Origin/Referer check (reusa COINBOT_ALLOWED_ORIGINS).
 *   4. Modelos y parámetros validados / acotados (el cliente no elige el modelo).
 *   5. Logging completo.
 */
@Slf4j
@RestController
@RequestMapping("/api/coinbot/ai/tts")
public class BotTtsController {

    @Value("${coincidir.elevenlabs-key:}")
    private String elevenLabsKey;

    @Value("${coincidir.openai-key:}")
    private String openaiKey;

    @Value("${coincidir.allowed-origins:}")
    private String allowedOriginsCsv;

    private static final String ELEVENLABS_URL_PREFIX = "https://api.elevenlabs.io/v1/text-to-speech/";
    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";

    /** Modelos por defecto si el cliente no especifica. */
    private static final String DEFAULT_ELEVENLABS_MODEL = "eleven_flash_v2_5";
    private static final String DEFAULT_OPENAI_MODEL = "tts-1";

    /** Whitelist de modelos aceptados del cliente. */
    private static final Set<String> ALLOWED_ELEVENLABS_MODELS = Set.of(
            "eleven_flash_v2_5", "eleven_flash_v2", "eleven_turbo_v2_5",
            "eleven_turbo_v2", "eleven_multilingual_v2"
    );
    private static final Set<String> ALLOWED_OPENAI_MODELS = Set.of("tts-1", "tts-1-hd");
    private static final Set<String> ALLOWED_OPENAI_VOICES = Set.of(
            "nova", "alloy", "echo", "fable", "onyx", "shimmer"
    );

    /** Tope de caracteres por request. ElevenLabs cobra por carácter. */
    private static final int MAX_TEXT_LENGTH = 5000;

    /** Rate limit: 30 requests/min por IP. Compartido bucket-key con BotAi. */
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;
    private static final int RATE_LIMIT_MAX = 30;
    private static final Map<String, Deque<Long>> rateLimitBuckets = new ConcurrentHashMap<>();

    /** Pattern para validar voiceId de ElevenLabs (alfanumérico). */
    private static final java.util.regex.Pattern VOICE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,40}$");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    //  ELEVENLABS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Síntesis con ElevenLabs. El frontend manda:
     *   {
     *     "text": "hola como estas",
     *     "voiceId": "abc123...",
     *     "model": "eleven_flash_v2_5",         (opcional)
     *     "languageCode": "es",                 (opcional)
     *     "stability": 0.40,                    (opcional, 0-1)
     *     "similarityBoost": 0.75,              (opcional, 0-1)
     *     "style": 0.50,                        (opcional, 0-1)
     *     "speed": 1.0                          (opcional, 0.5-2.0)
     *   }
     * Devuelve audio binario (mp3) o error JSON.
     */
    @PostMapping("/elevenlabs")
    public ResponseEntity<byte[]> ttsElevenLabs(@RequestBody Map<String, Object> req,
                                                 HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        String origin = httpReq.getHeader("Origin");

        if (elevenLabsKey == null || elevenLabsKey.isBlank()) {
            log.error("[Tts/elevenlabs] ELEVENLABS_API_KEY no configurada");
            return errorAudio(HttpStatus.INTERNAL_SERVER_ERROR, "ElevenLabs no disponible");
        }
        if (!isOriginAllowed(origin)) {
            log.warn("[Tts/elevenlabs] origin rechazado: '{}' (ip={})", origin, ip);
            return errorAudio(HttpStatus.FORBIDDEN, "origin no permitido");
        }
        if (!checkRateLimit(ip)) {
            log.warn("[Tts/elevenlabs] rate limit excedido (ip={})", ip);
            return errorAudio(HttpStatus.TOO_MANY_REQUESTS, "rate limit");
        }

        // Validar texto
        Object textObj = req == null ? null : req.get("text");
        if (!(textObj instanceof String) || ((String) textObj).isBlank()) {
            return errorAudio(HttpStatus.BAD_REQUEST, "text requerido");
        }
        String text = (String) textObj;
        if (text.length() > MAX_TEXT_LENGTH) {
            log.warn("[Tts/elevenlabs] text demasiado largo: {} chars (ip={})", text.length(), ip);
            return errorAudio(HttpStatus.PAYLOAD_TOO_LARGE, "text demasiado largo");
        }

        // Validar voiceId
        Object voiceObj = req.get("voiceId");
        if (!(voiceObj instanceof String) || !VOICE_ID_PATTERN.matcher((String) voiceObj).matches()) {
            return errorAudio(HttpStatus.BAD_REQUEST, "voiceId inválido");
        }
        String voiceId = (String) voiceObj;

        // Modelo: whitelist o default
        String model = asString(req.get("model"), DEFAULT_ELEVENLABS_MODEL);
        if (!ALLOWED_ELEVENLABS_MODELS.contains(model)) {
            log.warn("[Tts/elevenlabs] modelo no permitido: '{}' (ip={})", model, ip);
            model = DEFAULT_ELEVENLABS_MODEL;
        }

        // Construir body para ElevenLabs (no se pasa voice_settings extra del cliente)
        ObjectNode body = MAPPER.createObjectNode();
        body.put("text", text);
        body.put("model_id", model);
        String langCode = asString(req.get("languageCode"), null);
        if (langCode != null && langCode.matches("^[a-z]{2}(-[A-Za-z0-9]{2,8})?$")) {
            body.put("language_code", langCode);
        }
        ObjectNode voiceSettings = body.putObject("voice_settings");
        voiceSettings.put("stability",        clamp01(asDouble(req.get("stability"),       0.40)));
        voiceSettings.put("similarity_boost", clamp01(asDouble(req.get("similarityBoost"), 0.75)));
        voiceSettings.put("style",            clamp01(asDouble(req.get("style"),           0.50)));
        voiceSettings.put("use_speaker_boost", true);
        voiceSettings.put("speed",            clampRange(asDouble(req.get("speed"), 1.0), 0.5, 2.0));

        long startNs = System.nanoTime();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ELEVENLABS_URL_PREFIX + voiceId))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", elevenLabsKey)
                    .header("Accept", "audio/mpeg")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofByteArray());

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[Tts/elevenlabs] status={} elapsed={}ms ip={} chars={} model={} audioBytes={}",
                    response.statusCode(), elapsedMs, ip, text.length(), model, response.body().length);

            if (response.statusCode() >= 400) {
                // El body de error es JSON, no audio. Logueamos y devolvemos JSON de error.
                String errMsg = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[Tts/elevenlabs] ElevenLabs error: {}", errMsg);
                return errorAudio(HttpStatus.valueOf(response.statusCode()), "ElevenLabs error");
            }

            // Audio OK
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.parseMediaType("audio/mpeg"));
            responseHeaders.set("X-Tts-Chars", String.valueOf(text.length())); // útil para reportApiUsage en frontend
            responseHeaders.set("X-Tts-Model", model);
            responseHeaders.setAccessControlExposeHeaders(List.of("X-Tts-Chars", "X-Tts-Model"));
            return new ResponseEntity<>(response.body(), responseHeaders, HttpStatus.OK);

        } catch (Exception e) {
            log.error("[Tts/elevenlabs] error: {}", e.getMessage(), e);
            return errorAudio(HttpStatus.BAD_GATEWAY, "error comunicando con ElevenLabs");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  OPENAI TTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Síntesis con OpenAI TTS (fallback). El frontend manda:
     *   {
     *     "text": "hola como estas",
     *     "voice": "nova",            (opcional, default nova)
     *     "model": "tts-1",           (opcional, default tts-1)
     *     "speed": 1.0                (opcional, 0.25-4.0)
     *   }
     * Devuelve audio binario (mp3).
     */
    @PostMapping("/openai")
    public ResponseEntity<byte[]> ttsOpenAi(@RequestBody Map<String, Object> req,
                                             HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        String origin = httpReq.getHeader("Origin");

        if (openaiKey == null || openaiKey.isBlank()) {
            log.error("[Tts/openai] OPENAI_API_KEY no configurada");
            return errorAudio(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI no disponible");
        }
        if (!isOriginAllowed(origin)) {
            log.warn("[Tts/openai] origin rechazado: '{}' (ip={})", origin, ip);
            return errorAudio(HttpStatus.FORBIDDEN, "origin no permitido");
        }
        if (!checkRateLimit(ip)) {
            log.warn("[Tts/openai] rate limit excedido (ip={})", ip);
            return errorAudio(HttpStatus.TOO_MANY_REQUESTS, "rate limit");
        }

        Object textObj = req == null ? null : req.get("text");
        if (!(textObj instanceof String) || ((String) textObj).isBlank()) {
            return errorAudio(HttpStatus.BAD_REQUEST, "text requerido");
        }
        String text = (String) textObj;
        if (text.length() > MAX_TEXT_LENGTH) {
            return errorAudio(HttpStatus.PAYLOAD_TOO_LARGE, "text demasiado largo");
        }

        String voice = asString(req.get("voice"), "nova");
        if (!ALLOWED_OPENAI_VOICES.contains(voice)) {
            voice = "nova";
        }
        String model = asString(req.get("model"), DEFAULT_OPENAI_MODEL);
        if (!ALLOWED_OPENAI_MODELS.contains(model)) {
            model = DEFAULT_OPENAI_MODEL;
        }
        double speed = clampRange(asDouble(req.get("speed"), 1.0), 0.25, 4.0);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "mp3");
        body.put("speed", speed);

        long startNs = System.nanoTime();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_TTS_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + openaiKey)
                    .header("Accept", "audio/mpeg")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofByteArray());

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[Tts/openai] status={} elapsed={}ms ip={} chars={} model={} audioBytes={}",
                    response.statusCode(), elapsedMs, ip, text.length(), model, response.body().length);

            if (response.statusCode() >= 400) {
                String errMsg = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[Tts/openai] OpenAI error: {}", errMsg);
                return errorAudio(HttpStatus.valueOf(response.statusCode()), "OpenAI error");
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.parseMediaType("audio/mpeg"));
            responseHeaders.set("X-Tts-Chars", String.valueOf(text.length()));
            responseHeaders.set("X-Tts-Model", model);
            responseHeaders.setAccessControlExposeHeaders(List.of("X-Tts-Chars", "X-Tts-Model"));
            return new ResponseEntity<>(response.body(), responseHeaders, HttpStatus.OK);

        } catch (Exception e) {
            log.error("[Tts/openai] error: {}", e.getMessage(), e);
            return errorAudio(HttpStatus.BAD_GATEWAY, "error comunicando con OpenAI");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String asString(Object o, String fallback) {
        return (o instanceof String && !((String) o).isBlank()) ? (String) o : fallback;
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static double clamp01(double v) { return clampRange(v, 0.0, 1.0); }

    private static double clampRange(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, v));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    private static boolean checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        long cutoff = now - RATE_LIMIT_WINDOW_MS;
        Deque<Long> bucket = rateLimitBuckets.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.pollFirst();
            }
            if (bucket.size() >= RATE_LIMIT_MAX) return false;
            bucket.addLast(now);
        }
        if (rateLimitBuckets.size() > 10_000) {
            rateLimitBuckets.entrySet().removeIf(e -> {
                Deque<Long> b = e.getValue();
                synchronized (b) {
                    return b.isEmpty() || b.peekLast() < cutoff;
                }
            });
        }
        return true;
    }

    private boolean isOriginAllowed(String origin) {
        if (allowedOriginsCsv == null || allowedOriginsCsv.isBlank()) return true;
        if (origin == null || origin.isBlank()) return false;
        String host = origin.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        for (String allowed : allowedOriginsCsv.split(",")) {
            String a = allowed.trim();
            if (!a.isEmpty() && (a.equalsIgnoreCase(host) || host.endsWith("." + a))) return true;
        }
        return false;
    }

    /** Devuelve un body JSON serializado como bytes con el tipo de error,
     *  para clientes que esperan audio pero deben manejar errores. */
    private static ResponseEntity<byte[]> errorAudio(HttpStatus status, String message) {
        String json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), h, status);
    }
}
