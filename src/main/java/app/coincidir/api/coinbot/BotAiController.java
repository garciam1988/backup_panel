package app.coincidir.api.coinbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BotAiController — Endpoints de alto nivel para que el frontend hable con
 * Claude SIN exponer la API key.
 *
 * Reemplaza al AnthropicProxyController (que era un proxy genérico inseguro:
 * cualquiera podía elegir modelo y prompt, abriendo la puerta a abuso de
 * Opus/Sonnet). Acá el cliente NUNCA elige el modelo — el backend decide qué
 * modelo usar para cada feature.
 *
 * Endpoints:
 *   - POST /api/coinbot/ai/chat                  — bot conversacional (Sonnet 4.5 con tools)
 *   - POST /api/coinbot/ai/format-transcript     — corrección de voz (Sonnet 4)
 *   - POST /api/coinbot/ai/extract-client-data   — extracción de datos (Haiku 4.5)
 *
 * Defensas anti-abuso:
 *   1. Modelo fijo por endpoint (no se acepta del cliente).
 *   2. Rate limit por IP (ventana deslizante, 30 requests/min por IP).
 *   3. Payload size limit (ver MAX_BODY_BYTES).
 *   4. Origin/Referer check opcional (si COINBOT_ALLOWED_ORIGINS está configurado).
 *   5. Logging completo de cada request con IP y tamaño.
 *
 * Estos endpoints están bajo /api/coinbot/** que es público en SecurityConfig,
 * pero las defensas de arriba previenen el abuso desde el navegador del cliente.
 */
@Slf4j
@RestController
@RequestMapping("/api/coinbot/ai")
public class BotAiController {

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    /**
     * Lista CSV de orígenes permitidos (sin protocolo ni path). Ejemplo:
     *   asistente.yes-traveluy.com,bot.coincidir.app,localhost:5173
     * Si está vacío, se acepta cualquier origin (modo permisivo, NO recomendado
     * en producción). Configurar en Railway como COINBOT_ALLOWED_ORIGINS.
     */
    @Value("${coincidir.allowed-origins:}")
    private String allowedOriginsCsv;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    /** Modelos FIJOS — el cliente NO los elige. */
    private static final String MODEL_CHAT       = "claude-sonnet-4-5";
    private static final String MODEL_TRANSCRIPT = "claude-sonnet-4-20250514";
    private static final String MODEL_EXTRACT    = "claude-haiku-4-5-20251001";
    private static final String MODEL_GENERATE_PROMPT = "claude-sonnet-4-5";

    /** Topes de tokens de salida — el cliente NO los elige. */
    private static final int MAX_TOKENS_CHAT       = 1024;
    private static final int MAX_TOKENS_TRANSCRIPT = 1000;
    private static final int MAX_TOKENS_EXTRACT    = 500;
    private static final int MAX_TOKENS_GENERATE_PROMPT = 3500;

    /** Tope de tamaño del body recibido del cliente — corta cualquier intento de
     *  mandar un contexto gigante para drenar tokens. */
    private static final int MAX_BODY_BYTES = 200_000; // ~200 KB de JSON

    /** Rate limit: por IP, ventana deslizante de 60 segundos, máximo 30 requests. */
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;
    private static final int RATE_LIMIT_MAX = 30;
    private static final Map<String, Deque<Long>> rateLimitBuckets = new ConcurrentHashMap<>();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** System prompt fijo para el corrector de voz — el cliente NO lo elige. */
    private static final String SYSTEM_PROMPT_TRANSCRIPT =
            "Sos un corrector de texto en español rioplatense. Tu única tarea es " +
            "tomar el texto que te dan (transcripto de voz) y devolverlo con puntuación " +
            "correcta: signos de pregunta (¿?), signos de exclamación (¡!), comas, puntos, " +
            "mayúsculas al inicio de oración, y tildes/acentos donde corresponda. " +
            "NO cambies las palabras, NO agregues ni quites contenido. " +
            "Devolvé SOLO el texto corregido, sin comillas, sin explicaciones.";

    // ─────────────────────────────────────────────────────────────────────────
    //  ENDPOINT 1: Chat conversacional (Sonnet 4.5 con tools)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Forward del chat conversacional. El frontend manda:
     *   { "system": [...], "tools": [...], "messages": [...] }
     * Acá agregamos el modelo y max_tokens (fijos) y reenviamos a Anthropic.
     * No aceptamos `model` ni `max_tokens` del cliente.
     */
    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody String body, HttpServletRequest req) {
        return forward(body, req, MODEL_CHAT, MAX_TOKENS_CHAT, /*allowToolsAndSystem*/ true, /*systemOverride*/ null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ENDPOINT 2: Corrector de voz (Sonnet 4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Corrige puntuación/acentos de un transcript de voz. El frontend manda:
     *   { "text": "hola como estas" }
     * Nosotros construimos el request a Anthropic con el system prompt fijo.
     */
    @PostMapping("/format-transcript")
    public ResponseEntity<String> formatTranscript(@RequestBody Map<String, String> req,
                                                    HttpServletRequest httpReq) {
        String text = req == null ? null : req.get("text");
        if (text == null || text.isBlank()) {
            return jsonError(HttpStatus.BAD_REQUEST, "text requerido");
        }
        if (text.length() > 5000) {
            return jsonError(HttpStatus.PAYLOAD_TOO_LARGE, "text demasiado largo (max 5000 caracteres)");
        }
        ObjectNode anthropicBody = MAPPER.createObjectNode();
        ArrayNode messages = anthropicBody.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", text);
        return forward(anthropicBody.toString(), httpReq, MODEL_TRANSCRIPT, MAX_TOKENS_TRANSCRIPT,
                /*allowToolsAndSystem*/ false, /*systemOverride*/ SYSTEM_PROMPT_TRANSCRIPT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ENDPOINT 3: Extracción de datos del cliente (Haiku 4.5)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extrae datos del cliente de un transcript. El frontend manda:
     *   { "transcript": "Cliente: hola...\nBot: ..." }
     * Devuelve la respuesta de Anthropic tal cual (el frontend parsea el JSON).
     */
    @PostMapping("/extract-client-data")
    public ResponseEntity<String> extractClientData(@RequestBody Map<String, String> req,
                                                     HttpServletRequest httpReq) {
        String transcript = req == null ? null : req.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return jsonError(HttpStatus.BAD_REQUEST, "transcript requerido");
        }
        if (transcript.length() > 50_000) {
            return jsonError(HttpStatus.PAYLOAD_TOO_LARGE, "transcript demasiado largo (max 50000 caracteres)");
        }
        String extractionPrompt =
                "Analizá la siguiente conversación entre un cliente y un bot de atención. " +
                "Extraé los datos personales que el cliente haya mencionado. Devolvé SOLO un JSON válido, " +
                "sin texto antes ni después, sin ```json ni backticks. El JSON debe tener las claves: " +
                "{\"firstName\": string|null, \"lastName\": string|null, \"dni\": string|null, " +
                "\"email\": string|null, \"phone\": string|null}. " +
                "Si un dato no aparece en la conversación, poné null. Si aparece nombre completo, separá en firstName y lastName.\n\n" +
                "CONVERSACIÓN:\n" + transcript;

        ObjectNode anthropicBody = MAPPER.createObjectNode();
        ArrayNode messages = anthropicBody.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", extractionPrompt);
        return forward(anthropicBody.toString(), httpReq, MODEL_EXTRACT, MAX_TOKENS_EXTRACT,
                /*allowToolsAndSystem*/ false, /*systemOverride*/ null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ENDPOINT 4: Generador de prompts del admin (Sonnet 4.5)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Genera un prompt completo del bot a partir de un brief del admin.
     * Lo usa AdminPanel.handleGeneratePrompt. El system prompt es fijo (lo
     * decide el backend); el cliente solo manda system y messages opcionales.
     *
     * Diferencia con /chat: este endpoint NO acepta tools, y tiene un
     * max_tokens más alto (3500) porque la salida es un JSON grande.
     */
    @PostMapping("/generate-prompt")
    public ResponseEntity<String> generatePrompt(@RequestBody String body, HttpServletRequest req) {
        return forward(body, req, MODEL_GENERATE_PROMPT, MAX_TOKENS_GENERATE_PROMPT,
                /*allowToolsAndSystem*/ true, /*systemOverride*/ null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CORE — forward al Anthropic API con todas las defensas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reenvía a Anthropic aplicando todas las defensas. El modelo y max_tokens
     * los pisa el backend siempre, ignorando lo que mandó el cliente.
     *
     * @param clientBody    body JSON tal como llegó del cliente
     * @param req           request HTTP (para obtener IP y headers)
     * @param model         modelo FIJO a usar (no se acepta del cliente)
     * @param maxTokens     max_tokens FIJO (no se acepta del cliente)
     * @param allowToolsAndSystem  si true, se respetan tools y system del cliente
     *                             (usado en /chat). Si false, se ignoran.
     * @param systemOverride si no es null, se fuerza este system prompt
     */
    private ResponseEntity<String> forward(String clientBody, HttpServletRequest req,
                                            String model, int maxTokens,
                                            boolean allowToolsAndSystem, String systemOverride) {
        long startNs = System.nanoTime();
        String ip = clientIp(req);
        String origin = req.getHeader("Origin");

        // 1. Verificar que tenemos la key configurada
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.error("[BotAi/{}] ANTHROPIC_API_KEY no configurada en el servidor", model);
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "AI no disponible (key no configurada)");
        }

        // 2. Origin check (si está configurado)
        if (!isOriginAllowed(origin)) {
            log.warn("[BotAi/{}] origin rechazado: '{}' (ip={})", model, origin, ip);
            return jsonError(HttpStatus.FORBIDDEN, "origin no permitido");
        }

        // 3. Payload size limit
        if (clientBody != null && clientBody.length() > MAX_BODY_BYTES) {
            log.warn("[BotAi/{}] payload demasiado grande: {} bytes (ip={})", model, clientBody.length(), ip);
            return jsonError(HttpStatus.PAYLOAD_TOO_LARGE, "payload demasiado grande");
        }

        // 4. Rate limit
        if (!checkRateLimit(ip)) {
            log.warn("[BotAi/{}] rate limit excedido (ip={})", model, ip);
            return jsonError(HttpStatus.TOO_MANY_REQUESTS, "demasiadas requests, esperá un momento");
        }

        // 5. Construir el body final pisando model/max_tokens y limpiando lo que
        //    el cliente no debería poder elegir.
        String finalBody;
        try {
            JsonNode parsed = (clientBody == null || clientBody.isBlank())
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(clientBody);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("model", model);
            out.put("max_tokens", maxTokens);

            // messages: obligatorio, viene del cliente
            JsonNode messages = parsed.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return jsonError(HttpStatus.BAD_REQUEST, "messages requerido");
            }
            out.set("messages", messages);

            // system: forzado o del cliente (si está permitido)
            if (systemOverride != null) {
                out.put("system", systemOverride);
            } else if (allowToolsAndSystem && parsed.has("system")) {
                out.set("system", parsed.get("system"));
            }

            // tools: solo si está permitido (chat conversacional)
            if (allowToolsAndSystem && parsed.has("tools")) {
                out.set("tools", parsed.get("tools"));
            }

            // metadata: NUNCA del cliente — evitamos que un atacante etiquete
            // sus sesiones con nuestro user_id legítimo.
            // (no la copiamos)

            finalBody = MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("[BotAi/{}] body inválido (ip={}): {}", model, ip, e.getMessage());
            return jsonError(HttpStatus.BAD_REQUEST, "body JSON inválido");
        }

        // 6. Forward a Anthropic
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(finalBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[BotAi/{}] OK status={} elapsed={}ms ip={} bodyIn={}B bodyOut={}B",
                    model, response.statusCode(), elapsedMs, ip,
                    finalBody.length(), response.body().length());

            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());

        } catch (Exception e) {
            log.error("[BotAi/{}] error llamando Anthropic (ip={}): {}", model, ip, e.getMessage(), e);
            return jsonError(HttpStatus.BAD_GATEWAY, "error comunicando con Anthropic: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Obtiene la IP real del cliente, considerando X-Forwarded-For (Railway/proxy). */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Primer IP de la lista (cliente original)
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /** Rate limit por IP con ventana deslizante. */
    private static boolean checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        long cutoff = now - RATE_LIMIT_WINDOW_MS;
        Deque<Long> bucket = rateLimitBuckets.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (bucket) {
            // Sacar timestamps fuera de la ventana
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.pollFirst();
            }
            if (bucket.size() >= RATE_LIMIT_MAX) {
                return false;
            }
            bucket.addLast(now);
        }
        // Limpieza ocasional para no crecer la memoria con IPs viejas
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

    /** Verifica que el Origin venga de un dominio permitido (si está configurado). */
    private boolean isOriginAllowed(String origin) {
        if (allowedOriginsCsv == null || allowedOriginsCsv.isBlank()) {
            // Sin whitelist configurada → modo permisivo (compat). Configurar
            // COINBOT_ALLOWED_ORIGINS en Railway para activar el filtro.
            return true;
        }
        if (origin == null || origin.isBlank()) {
            // Si la whitelist está configurada, exigimos Origin presente.
            return false;
        }
        // Extraer host del origin: "https://asistente.yes-traveluy.com" → "asistente.yes-traveluy.com"
        String host = origin.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        for (String allowed : allowedOriginsCsv.split(",")) {
            String a = allowed.trim();
            if (!a.isEmpty() && (a.equalsIgnoreCase(host) || host.endsWith("." + a))) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<String> jsonError(HttpStatus status, String message) {
        String body = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
