package app.coincidir.api.manager;

import app.coincidir.api.domain.ManagerConfig;
import app.coincidir.api.repository.ManagerConfigRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * ManagerConfigController — CRUD del singleton {@code manager_config}.
 *
 * Endpoints (todos bajo /api/admin/**, requieren JWT por SecurityConfig):
 *   GET  /api/admin/manager-config        → lectura
 *   PUT  /api/admin/manager-config        → guardado parcial (solo campos no-null)
 *   POST /api/admin/manager-config/reset  → restaura defaults
 *
 * El frontend de admin (ManagerSection.jsx) usa estos endpoints.
 * El frontend del manager (ManagerApp.jsx) usa {@link ManagerRuntimeController}.
 *
 * Patrón idéntico a {@link app.coincidir.api.coinbot.BotConfigController}; si
 * cambia la convención allá (auth, paths, response shape), cambiar acá también.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/manager-config")
@RequiredArgsConstructor
public class ManagerConfigController {

    private static final Long SINGLETON_ID = 1L;

    private final ManagerConfigRepository repo;

    @GetMapping
    @Transactional
    public ManagerConfigDto get() {
        ManagerConfig entity = repo.findById(SINGLETON_ID).orElseGet(() -> {
            log.warn("⚠️ [manager_config] No existe config — creando default.");
            return createDefault();
        });
        return ManagerConfigDto.fromEntity(entity);
    }

    @PutMapping
    @Transactional
    public ManagerConfigDto update(@RequestBody ManagerConfigDto dto, Authentication auth) {
        ManagerConfig entity = repo.findById(SINGLETON_ID).orElseGet(this::createDefault);

        // Aplicar el DTO sobre la entidad. Strings: null-safe.
        if (dto.displayName != null)        entity.setDisplayName(dto.displayName);
        if (dto.wakeWord != null)           entity.setWakeWord(normalizeWakeWord(dto.wakeWord));
        if (dto.tagline != null)            entity.setTagline(dto.tagline);
        if (dto.themeColor != null)         entity.setThemeColor(dto.themeColor);
        if (dto.accentColor != null)        entity.setAccentColor(dto.accentColor);

        if (dto.llmModel != null)           entity.setLlmModel(dto.llmModel);
        if (dto.maxTokens != null && dto.maxTokens > 0) entity.setMaxTokens(dto.maxTokens);
        if (dto.systemPrompt != null)       entity.setSystemPrompt(dto.systemPrompt);

        if (dto.ttsEnabled != null)         entity.setTtsEnabled(dto.ttsEnabled);
        if (dto.ttsProvider != null)        entity.setTtsProvider(dto.ttsProvider);
        if (dto.elevenlabsVoiceId != null)  entity.setElevenlabsVoiceId(dto.elevenlabsVoiceId);
        if (dto.elevenlabsModel != null)    entity.setElevenlabsModel(dto.elevenlabsModel);
        if (dto.ttsStability != null)       entity.setTtsStability(dto.ttsStability);
        if (dto.ttsSimilarity != null)      entity.setTtsSimilarity(dto.ttsSimilarity);
        if (dto.ttsSpeed != null)           entity.setTtsSpeed(dto.ttsSpeed);
        if (dto.openaiTtsVoice != null)     entity.setOpenaiTtsVoice(dto.openaiTtsVoice);

        if (dto.wakeWordEnabled != null)    entity.setWakeWordEnabled(dto.wakeWordEnabled);
        if (dto.sttLanguage != null)        entity.setSttLanguage(dto.sttLanguage);
        if (dto.silenceTimeoutMs != null && dto.silenceTimeoutMs > 200)
            entity.setSilenceTimeoutMs(dto.silenceTimeoutMs);

        if (dto.toolsWhitelistJson != null) entity.setToolsWhitelistJson(dto.toolsWhitelistJson);

        if (dto.maxPopups != null && dto.maxPopups > 0) entity.setMaxPopups(dto.maxPopups);
        if (dto.autoClosePopupsMinutes != null) entity.setAutoClosePopupsMinutes(dto.autoClosePopupsMinutes);
        if (dto.quickCommandsJson != null)  entity.setQuickCommandsJson(dto.quickCommandsJson);

        if (dto.logConversations != null)   entity.setLogConversations(dto.logConversations);

        entity.setUpdatedAt(Instant.now());
        if (auth != null && auth.getName() != null) {
            entity.setUpdatedBy(auth.getName());
        }

        ManagerConfig saved = repo.save(entity);
        log.info("[manager_config] Actualizado por {}", entity.getUpdatedBy());
        return ManagerConfigDto.fromEntity(saved);
    }

    @PostMapping("/reset")
    @Transactional
    public ManagerConfigDto reset(Authentication auth) {
        repo.deleteById(SINGLETON_ID);
        ManagerConfig fresh = createDefault();
        if (auth != null && auth.getName() != null) {
            fresh.setUpdatedBy(auth.getName());
        }
        repo.save(fresh);
        log.info("[manager_config] Reset a defaults por {}", auth == null ? "?" : auth.getName());
        return ManagerConfigDto.fromEntity(fresh);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ManagerConfig createDefault() {
        ManagerConfig c = ManagerConfig.builder()
                .id(SINGLETON_ID)
                .displayName("JARVIS")
                .wakeWord("jarvis")
                .tagline("Asistente operativo")
                .themeColor("#00d4ff")
                .accentColor("#ffb84d")
                .llmModel("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                .ttsEnabled(true)
                .ttsProvider("elevenlabs")
                .elevenlabsModel("eleven_turbo_v2_5")
                .ttsStability(0.5)
                .ttsSimilarity(0.75)
                .ttsSpeed(1.05)
                .openaiTtsVoice("onyx")
                .wakeWordEnabled(true)
                .sttLanguage("es-AR")
                .silenceTimeoutMs(1400)
                .toolsWhitelistJson(
                        "{\"botTools\":[\"*\"],\"botApiTools\":[\"*\"]," +
                        "\"botTableTools\":[\"*\"],\"loyaltyTools\":[\"*\"]," +
                        "\"sqlExec\":{\"enabled\":false,\"connectorIds\":[]}}"
                )
                .maxPopups(4)
                .autoClosePopupsMinutes(0)
                .quickCommandsJson("[]")
                .logConversations(true)
                .updatedAt(Instant.now())
                .build();
        return repo.save(c);
    }

    private static String normalizeWakeWord(String s) {
        if (s == null) return null;
        return s.trim().toLowerCase();
    }

    private static final String DEFAULT_SYSTEM_PROMPT =
        "Sos el asistente operativo interno de la empresa. Tu interlocutor es un manager " +
        "que ya te conoce, así que NO te presentás, NO saludás de más, vas directo al grano.\n\n" +
        "Hablás en español rioplatense, profesional pero amigable. Respuestas cortas " +
        "(máximo 2 oraciones cuando devolvés datos: el gráfico/tabla habla por sí solo).\n\n" +
        "REGLAS:\n" +
        "1. Si el pedido necesita datos REALES del sistema, USÁ una tool del Bot Platform. Nunca inventes números reales.\n" +
        "2. Si el pedido es una SIMULACIÓN, ejemplo, mockup o estimación hipotética, usá la tool `render_visual` " +
        "para abrir un popup con la visualización (chart_bar, chart_line, table, kpi o document). " +
        "Generá los datos plausibles vos mismo y pasalos a la tool.\n" +
        "3. Cuando uses render_visual, NO repitas los datos en el texto de respuesta — el popup ya los muestra. " +
        "Decí UNA oración con el insight clave y listo.\n" +
        "4. Si es charla general, respondé corto sin tools.\n" +
        "5. Si el pedido es ambiguo, pedí UNA aclaración puntual antes de invocar tools.";

    // ═════════════════════════════════════════════════════════════════════════
    // DTO inline (mismo patrón que BotConfigController.BotConfigDto)
    // ═════════════════════════════════════════════════════════════════════════
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ManagerConfigDto {
        public Long id;

        // Identidad / branding
        public String displayName;
        public String wakeWord;
        public String tagline;
        public String themeColor;
        public String accentColor;

        // LLM
        public String llmModel;
        public Integer maxTokens;
        public String systemPrompt;

        // TTS
        public Boolean ttsEnabled;
        public String ttsProvider;
        public String elevenlabsVoiceId;
        public String elevenlabsModel;
        public Double ttsStability;
        public Double ttsSimilarity;
        public Double ttsSpeed;
        public String openaiTtsVoice;

        // Wake / STT
        public Boolean wakeWordEnabled;
        public String sttLanguage;
        public Integer silenceTimeoutMs;

        // Tools
        public String toolsWhitelistJson;

        // UI / Behavior
        public Integer maxPopups;
        public Integer autoClosePopupsMinutes;
        public String quickCommandsJson;

        // Logging
        public Boolean logConversations;

        // Audit
        public Instant updatedAt;
        public String updatedBy;

        public static ManagerConfigDto fromEntity(ManagerConfig e) {
            ManagerConfigDto d = new ManagerConfigDto();
            d.id = e.getId();
            d.displayName = e.getDisplayName();
            d.wakeWord = e.getWakeWord();
            d.tagline = e.getTagline();
            d.themeColor = e.getThemeColor();
            d.accentColor = e.getAccentColor();
            d.llmModel = e.getLlmModel();
            d.maxTokens = e.getMaxTokens();
            d.systemPrompt = e.getSystemPrompt();
            d.ttsEnabled = e.isTtsEnabled();
            d.ttsProvider = e.getTtsProvider();
            d.elevenlabsVoiceId = e.getElevenlabsVoiceId();
            d.elevenlabsModel = e.getElevenlabsModel();
            d.ttsStability = e.getTtsStability();
            d.ttsSimilarity = e.getTtsSimilarity();
            d.ttsSpeed = e.getTtsSpeed();
            d.openaiTtsVoice = e.getOpenaiTtsVoice();
            d.wakeWordEnabled = e.isWakeWordEnabled();
            d.sttLanguage = e.getSttLanguage();
            d.silenceTimeoutMs = e.getSilenceTimeoutMs();
            d.toolsWhitelistJson = e.getToolsWhitelistJson();
            d.maxPopups = e.getMaxPopups();
            d.autoClosePopupsMinutes = e.getAutoClosePopupsMinutes();
            d.quickCommandsJson = e.getQuickCommandsJson();
            d.logConversations = e.isLogConversations();
            d.updatedAt = e.getUpdatedAt();
            d.updatedBy = e.getUpdatedBy();
            return d;
        }
    }
}
