package app.coincidir.api.manager;

import app.coincidir.api.apiusage.domain.ApiUsageLog;
import app.coincidir.api.apiusage.service.ApiUsageService;
import app.coincidir.api.domain.ManagerConfig;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.ManagerConfigRepository;
import app.coincidir.api.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ManagerRuntimeController — endpoints que consume el frontend {@code /manager}.
 *
 * IMPORTANTE: TODOS estos endpoints requieren:
 *   1. JWT válido (SecurityConfig).
 *   2. Que el {@link UserAccount} asociado tenga {@code managerAccess = true}.
 *
 * Endpoints:
 *   GET  /api/manager/config                → config completa para inicializar UI
 *   POST /api/manager/llm/messages          → proxy autenticado a Anthropic
 *                                             (reemplaza el AnthropicProxyController
 *                                             deshabilitado, ahora con auth+rate+log)
 *   POST /api/manager/tts/elevenlabs        → proxy a ElevenLabs (audio mp3)
 *   POST /api/manager/tts/openai            → proxy a OpenAI TTS (fallback)
 *
 * Rate limit: in-memory por user, 60 req/min. Para producción multi-instancia,
 * mover a Redis.
 */
@Slf4j
@RestController
@RequestMapping("/api/manager")
@RequiredArgsConstructor
public class ManagerRuntimeController {

    private static final Long SINGLETON_ID = 1L;
    private static final int RATE_LIMIT_PER_MINUTE = 60;

    /**
     * Whitelist de modelos LLM permitidos. Si el frontend pide otro, 400.
     * Evita que alguien autenticado pida claude-opus-* y nos funda la cuenta.
     */
    private static final Set<String> ALLOWED_LLM_MODELS = Set.of(
            "claude-sonnet-4-20250514",
            "claude-haiku-4-5-20251001",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022"
    );

    private final ManagerConfigRepository configRepo;
    private final UserAccountRepository userRepo;
    private final ApiUsageService usageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${coincidir.anthropic-key:}")
    private String anthropicApiKey;

    @Value("${coincidir.elevenlabs-key:}")
    private String elevenlabsApiKey;

    @Value("${coincidir.openai-key:}")
    private String openaiApiKey;

    @Value("${coincidir.anthropic-version:2023-06-01}")
    private String anthropicVersion;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Rate limiter simple por user (in-memory) ─────────────────────────────
    private static final class Bucket {
        long windowStartMs;
        int count;
    }
    private final Map<Long, Bucket> rateBuckets = new ConcurrentHashMap<>();

    /**
     * Valida JWT + manager_access. Retorna el UserAccount si OK,
     * lanza ResponseStatusException(403) si no.
     */
    private UserAccount requireManagerUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Auth requerida");
        }
        UserAccount u = userRepo.findByEmail(auth.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        if (!Boolean.TRUE.equals(u.getManagerAccess())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El usuario no tiene acceso al /manager (manager_access=false)");
        }
        return u;
    }

    private void checkRateLimit(Long userId) {
        long now = System.currentTimeMillis();
        Bucket b = rateBuckets.computeIfAbsent(userId, k -> new Bucket());
        synchronized (b) {
            if (now - b.windowStartMs > 60_000) {
                b.windowStartMs = now;
                b.count = 0;
            }
            b.count++;
            if (b.count > RATE_LIMIT_PER_MINUTE) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limit: máximo " + RATE_LIMIT_PER_MINUTE + " requests/min");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/manager/config
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/config")
    @Transactional
    public ManagerConfigController.ManagerConfigDto getConfig(Authentication auth) {
        requireManagerUser(auth);
        ManagerConfig cfg = configRepo.findById(SINGLETON_ID).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Manager config no inicializada. Pedile al admin que entre a /admin → Manager."));
        return ManagerConfigController.ManagerConfigDto.fromEntity(cfg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/manager/llm/messages   — proxy a Anthropic con auth + log
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/llm/messages")
    public ResponseEntity<String> proxyAnthropic(
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        UserAccount user = requireManagerUser(auth);
        checkRateLimit(user.getId());

        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.error("[manager] anthropic.api-key no configurada en application.yml");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "API key de Anthropic no configurada en el servidor");
        }

        String model = String.valueOf(body.getOrDefault("model", ""));
        if (!ALLOWED_LLM_MODELS.contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Modelo no permitido: '" + model + "'. Whitelist: " + ALLOWED_LLM_MODELS);
        }

        Object mt = body.get("max_tokens");
        if (mt instanceof Number n && n.intValue() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "max_tokens muy alto. Máximo permitido: 4096");
        }

        long t0 = System.currentTimeMillis();
        String sessionId = String.valueOf(body.getOrDefault("_sessionId", ""));
        body.remove("_sessionId");

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", anthropicVersion)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            int inputTokens = 0, outputTokens = 0;
            try {
                JsonNode json = objectMapper.readTree(resp.body());
                JsonNode usage = json.get("usage");
                if (usage != null) {
                    inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                    outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                }
            } catch (Exception ignored) { }

            try {
                ApiUsageLog logRow = new ApiUsageLog();
                logRow.setProvider("anthropic");
                logRow.setModel(model);
                logRow.setInputTokens(inputTokens);
                logRow.setOutputTokens(outputTokens);
                logRow.setFeature("manager");
                logRow.setSessionId(sessionId.isBlank() ? null : sessionId);
                logRow.setCreatedAt(Instant.now());
                usageService.recordUsage(logRow);
            } catch (Exception e) {
                log.warn("[manager] No pude loguear api_usage: {}", e.getMessage());
            }

            log.info("[manager] LLM call user={} model={} in={} out={} elapsedMs={}",
                    user.getEmail(), model, inputTokens, outputTokens, System.currentTimeMillis() - t0);

            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(resp.body());

        } catch (java.net.http.HttpTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Timeout a Anthropic");
        } catch (Exception e) {
            log.error("[manager] Error en proxy Anthropic", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al consultar el modelo: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/manager/tts/elevenlabs
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/tts/elevenlabs")
    public ResponseEntity<byte[]> proxyElevenLabs(
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        UserAccount user = requireManagerUser(auth);
        checkRateLimit(user.getId());

        if (elevenlabsApiKey == null || elevenlabsApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ElevenLabs API key no configurada");
        }

        String voiceId = String.valueOf(body.getOrDefault("voiceId", ""));
        String text    = String.valueOf(body.getOrDefault("text", ""));
        if (voiceId.isBlank() || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "voiceId y text son requeridos");
        }
        if (text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Texto muy largo (max 2000 chars)");
        }
        String model = String.valueOf(body.getOrDefault("model", "eleven_turbo_v2_5"));

        Map<String, Object> elBody = new HashMap<>();
        elBody.put("text", text);
        elBody.put("model_id", model);
        Map<String, Object> voiceSettings = new HashMap<>();
        if (body.get("stability") instanceof Number n)  voiceSettings.put("stability", n.doubleValue());
        if (body.get("similarity") instanceof Number n) voiceSettings.put("similarity_boost", n.doubleValue());
        if (body.get("style") instanceof Number n)      voiceSettings.put("style", n.doubleValue());
        if (body.get("speed") instanceof Number n)      voiceSettings.put("speed", n.doubleValue());
        if (!voiceSettings.isEmpty()) elBody.put("voice_settings", voiceSettings);

        try {
            String jsonBody = objectMapper.writeValueAsString(elBody);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId))
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", elevenlabsApiKey)
                    .header("Accept", "audio/mpeg")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() >= 400) {
                String errBody = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[manager] ElevenLabs error {}: {}", resp.statusCode(),
                        errBody.substring(0, Math.min(200, errBody.length())));
                throw new ResponseStatusException(HttpStatus.valueOf(resp.statusCode()),
                        "ElevenLabs: " + errBody);
            }

            try {
                ApiUsageLog logRow = new ApiUsageLog();
                logRow.setProvider("elevenlabs");
                logRow.setModel(model);
                logRow.setCharacters(text.length());
                logRow.setFeature("manager");
                logRow.setCreatedAt(Instant.now());
                usageService.recordUsage(logRow);
            } catch (Exception ignored) {}

            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("audio/mpeg"))
                    .body(resp.body());

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[manager] Error en proxy ElevenLabs", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ElevenLabs: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/manager/tts/openai (fallback)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/tts/openai")
    public ResponseEntity<byte[]> proxyOpenAiTts(
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        UserAccount user = requireManagerUser(auth);
        checkRateLimit(user.getId());

        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenAI API key no configurada");
        }

        String text  = String.valueOf(body.getOrDefault("text", ""));
        String voice = String.valueOf(body.getOrDefault("voice", "onyx"));
        String model = String.valueOf(body.getOrDefault("model", "tts-1"));
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text requerido");
        }
        if (text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Texto muy largo (max 2000)");
        }

        Map<String, Object> oaBody = new HashMap<>();
        oaBody.put("model", model);
        oaBody.put("voice", voice);
        oaBody.put("input", text);
        oaBody.put("response_format", "mp3");

        try {
            String jsonBody = objectMapper.writeValueAsString(oaBody);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() >= 400) {
                String errBody = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
                throw new ResponseStatusException(HttpStatus.valueOf(resp.statusCode()),
                        "OpenAI: " + errBody);
            }

            try {
                ApiUsageLog logRow = new ApiUsageLog();
                logRow.setProvider("openai");
                logRow.setModel(model);
                logRow.setCharacters(text.length());
                logRow.setFeature("manager");
                logRow.setCreatedAt(Instant.now());
                usageService.recordUsage(logRow);
            } catch (Exception ignored) {}

            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("audio/mpeg"))
                    .body(resp.body());

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[manager] Error en proxy OpenAI TTS", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI: " + e.getMessage());
        }
    }
}
