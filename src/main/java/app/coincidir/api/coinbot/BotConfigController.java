package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * BotConfigController — CRUD del singleton bot_config.
 *
 * Endpoints (todos bajo /api/admin/**, requieren JWT por SecurityConfig):
 *   GET  /api/admin/bot-config        → lectura (para AdminPanel y bot web)
 *   PUT  /api/admin/bot-config        → guardado (solo desde AdminPanel)
 *   POST /api/admin/bot-config/reset  → restaura defaults
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-config")
@RequiredArgsConstructor
public class BotConfigController {

    private static final Long SINGLETON_ID = 1L;

    private final BotConfigRepository repo;

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/bot-config
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional
    public BotConfigDto get() {
        BotConfig entity = repo.findById(SINGLETON_ID).orElseGet(this::createDefault);
        return BotConfigDto.fromEntity(entity);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUT /api/admin/bot-config
    // ─────────────────────────────────────────────────────────────────────
    @PutMapping
    @Transactional
    public BotConfigDto update(@RequestBody BotConfigDto dto, Authentication auth) {
        BotConfig entity = repo.findById(SINGLETON_ID).orElseGet(this::createDefault);

        // Aplicar el DTO sobre la entidad. Strings: null-safe, booleans: default-safe.
        if (dto.botName != null)           entity.setBotName(dto.botName);
        if (dto.brandName != null)         entity.setBrandName(dto.brandName);
        if (dto.logoUrl != null)           entity.setLogoUrl(dto.logoUrl);
        if (dto.botAvatarUrl != null)      entity.setBotAvatarUrl(dto.botAvatarUrl);
        if (dto.themeJson != null)         entity.setThemeJson(dto.themeJson);
        if (dto.welcomeMessage != null)    entity.setWelcomeMessage(dto.welcomeMessage);

        if (dto.allowTts != null)          entity.setAllowTts(dto.allowTts);
        if (dto.allowUserAudio != null)    entity.setAllowUserAudio(dto.allowUserAudio);
        if (dto.useCustomVoice != null)    entity.setUseCustomVoice(dto.useCustomVoice);
        if (dto.customVoiceId != null)     entity.setCustomVoiceId(dto.customVoiceId);

        if (dto.customPrompt != null)      entity.setCustomPrompt(dto.customPrompt);
        if (dto.businessRulesJson != null) entity.setBusinessRulesJson(dto.businessRulesJson);

        // activePromptTemplateId puede ser Long, 0/negativo (clear) o null (no tocar).
        // Convención del frontend: envía 0 o -1 para "quitar plantilla", un Long >0 para setearla,
        // y omite el campo (o null) si no quiere modificarlo.
        if (dto.activePromptTemplateId != null) {
            if (dto.activePromptTemplateId <= 0) entity.setActivePromptTemplateId(null);
            else entity.setActivePromptTemplateId(dto.activePromptTemplateId);
        }

        if (dto.allowReadReceipts != null) entity.setAllowReadReceipts(dto.allowReadReceipts);
        if (dto.allowShowImages != null)   entity.setAllowShowImages(dto.allowShowImages);
        if (dto.allowSendEmails != null)   entity.setAllowSendEmails(dto.allowSendEmails);

        if (dto.showQuickAccess != null)   entity.setShowQuickAccess(dto.showQuickAccess);
        if (dto.quickAccessJson != null)   entity.setQuickAccessJson(dto.quickAccessJson);

        if (auth != null && auth.getName() != null) {
            entity.setUpdatedBy(auth.getName());
        }

        BotConfig saved = repo.save(entity);
        log.info("bot_config actualizado por {}", saved.getUpdatedBy());
        return BotConfigDto.fromEntity(saved);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/bot-config/reset — vuelve a defaults
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/reset")
    @Transactional
    public BotConfigDto reset(Authentication auth) {
        repo.findById(SINGLETON_ID).ifPresent(repo::delete);
        BotConfig entity = createDefault();
        if (auth != null && auth.getName() != null) {
            entity.setUpdatedBy(auth.getName());
            entity = repo.save(entity);
        }
        log.info("bot_config reseteada a defaults por {}", auth != null ? auth.getName() : "?");
        return BotConfigDto.fromEntity(entity);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private BotConfig createDefault() {
        BotConfig e = new BotConfig();
        e.setId(SINGLETON_ID);
        e.setBotName("Lety");
        e.setBrandName("YES Travel");
        e.setLogoUrl("/yes-travel-logo.jpg");
        e.setBotAvatarUrl("");
        e.setWelcomeMessage(
            "¡Hola! 👋 Soy **Lety**, de YES Travel.\n\n" +
            "Estoy acá para ayudarte con todo lo que necesites sobre **tu reserva** ✈️\n\n" +
            "¿Con qué puedo ayudarte hoy?"
        );
        e.setAllowTts(true);
        e.setAllowUserAudio(true);
        e.setUseCustomVoice(false);
        e.setCustomVoiceId("");
        e.setCustomPrompt("");           // vacío → frontend usa ORIGINAL_PROMPT
        e.setBusinessRulesJson("[]");
        e.setAllowReadReceipts(true);
        e.setAllowShowImages(true);
        e.setAllowSendEmails(true);
        e.setShowQuickAccess(true);
        e.setQuickAccessJson(
            "[" +
            "{\"id\":\"qa-1\",\"label\":\"📋 Mi reserva\",\"text\":\"Quiero consultar mi reserva\"}," +
            "{\"id\":\"qa-2\",\"label\":\"📄 Mis vouchers\",\"text\":\"Necesito mis vouchers\"}," +
            "{\"id\":\"qa-3\",\"label\":\"💳 Mis pagos\",\"text\":\"¿Cómo está mi plan de pagos?\"}," +
            "{\"id\":\"qa-4\",\"label\":\"✈️ Info de vuelo\",\"text\":\"¿Cuáles son los datos de mi vuelo?\"}" +
            "]"
        );
        return repo.save(e);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO público — los JSON arrays se exponen como strings crudos para que
    // el frontend los parsee una sola vez (es la forma en que los guarda).
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotConfigDto {
        public String botName;
        public String brandName;
        public String logoUrl;
        public String botAvatarUrl;
        public String themeJson;
        public String welcomeMessage;

        public Boolean allowTts;
        public Boolean allowUserAudio;
        public Boolean useCustomVoice;
        public String  customVoiceId;

        public String  customPrompt;
        public String  businessRulesJson;

        /** ID del BotPromptTemplate seleccionado. 0 o null → sin plantilla (usa customPrompt). */
        public Long    activePromptTemplateId;

        public Boolean allowReadReceipts;
        public Boolean allowShowImages;
        public Boolean allowSendEmails;

        public Boolean showQuickAccess;
        public String  quickAccessJson;

        public Instant updatedAt;
        public String  updatedBy;

        public static BotConfigDto fromEntity(BotConfig e) {
            BotConfigDto d = new BotConfigDto();
            d.botName                = e.getBotName();
            d.brandName              = e.getBrandName();
            d.logoUrl                = e.getLogoUrl();
            d.botAvatarUrl           = e.getBotAvatarUrl();
            d.themeJson              = e.getThemeJson();
            d.welcomeMessage         = e.getWelcomeMessage();
            d.allowTts               = e.getAllowTts();
            d.allowUserAudio         = e.getAllowUserAudio();
            d.useCustomVoice         = e.getUseCustomVoice();
            d.customVoiceId          = e.getCustomVoiceId();
            d.customPrompt           = e.getCustomPrompt();
            d.businessRulesJson      = e.getBusinessRulesJson();
            d.activePromptTemplateId = e.getActivePromptTemplateId();
            d.allowReadReceipts      = e.getAllowReadReceipts();
            d.allowShowImages        = e.getAllowShowImages();
            d.allowSendEmails        = e.getAllowSendEmails();
            d.showQuickAccess        = e.getShowQuickAccess();
            d.quickAccessJson        = e.getQuickAccessJson();
            d.updatedAt              = e.getUpdatedAt();
            d.updatedBy              = e.getUpdatedBy();
            return d;
        }
    }
}
