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
        if (dto.headerTitle != null)       entity.setHeaderTitle(dto.headerTitle);
        if (dto.headerSubtitle != null)    entity.setHeaderSubtitle(dto.headerSubtitle);
        if (dto.themeJson != null)         entity.setThemeJson(dto.themeJson);
        if (dto.welcomeMessage != null)    entity.setWelcomeMessage(dto.welcomeMessage);

        if (dto.allowTts != null)          entity.setAllowTts(dto.allowTts);
        if (dto.allowUserAudio != null)    entity.setAllowUserAudio(dto.allowUserAudio);
        if (dto.useCustomVoice != null)    entity.setUseCustomVoice(dto.useCustomVoice);
        if (dto.customVoiceId != null)     entity.setCustomVoiceId(dto.customVoiceId);
        if (dto.ttsModel != null)          entity.setTtsModel(dto.ttsModel);
        if (dto.ttsStability != null)      entity.setTtsStability(dto.ttsStability);
        if (dto.ttsSimilarity != null)     entity.setTtsSimilarity(dto.ttsSimilarity);
        if (dto.ttsStyle != null)          entity.setTtsStyle(dto.ttsStyle);
        if (dto.ttsSpeed != null)          entity.setTtsSpeed(dto.ttsSpeed);

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

        if (dto.conversationTimeoutMinutes != null && dto.conversationTimeoutMinutes > 0) {
            entity.setConversationTimeoutMinutes(dto.conversationTimeoutMinutes);
        }
        if (dto.dataRequestPrompt != null) entity.setDataRequestPrompt(dto.dataRequestPrompt);
        if (dto.fraudDetectionEnabled != null) entity.setFraudDetectionEnabled(dto.fraudDetectionEnabled);
        if (dto.fraudAlertEmails != null)      entity.setFraudAlertEmails(dto.fraudAlertEmails);
        if (dto.fraudEmailSubject != null)     entity.setFraudEmailSubject(dto.fraudEmailSubject);
        if (dto.fraudEmailTemplate != null)    entity.setFraudEmailTemplate(dto.fraudEmailTemplate);
        if (dto.enabledPanels != null)         entity.setEnabledPanels(dto.enabledPanels);
        if (dto.panelOrdersConfigJson != null) entity.setPanelOrdersConfigJson(dto.panelOrdersConfigJson);
        if (dto.menuEnabled != null)           entity.setMenuEnabled(dto.menuEnabled);
        if (dto.menuLabel != null)             entity.setMenuLabel(dto.menuLabel);
        if (dto.menuConfigJson != null)        entity.setMenuConfigJson(dto.menuConfigJson);
        if (dto.language != null)              entity.setLanguage(dto.language);
        if (dto.savedVoicesJson != null)       entity.setSavedVoicesJson(dto.savedVoicesJson);
        if (dto.websiteUrl != null)            entity.setWebsiteUrl(dto.websiteUrl);
        if (dto.websiteEnabled != null)        entity.setWebsiteEnabled(dto.websiteEnabled);

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
        e.setTtsModel("eleven_flash_v2_5");
        e.setTtsStability(0.40);
        e.setTtsSimilarity(0.75);
        e.setTtsStyle(0.50);
        e.setTtsSpeed(1.0);
        e.setCustomPrompt("");           // vacío → frontend usa ORIGINAL_PROMPT
        e.setBusinessRulesJson("[]");
        e.setAllowReadReceipts(true);
        e.setAllowShowImages(true);
        e.setAllowSendEmails(true);
        e.setShowQuickAccess(true);
        e.setConversationTimeoutMinutes(15);
        e.setDataRequestPrompt(null);
        e.setFraudDetectionEnabled(false);
        e.setFraudAlertEmails(null);
        e.setFraudEmailSubject("[Alerta fraude] {{brandName}} — {{clientName}}");
        e.setFraudEmailTemplate(
            "<!DOCTYPE html><html><body style=\"font-family: Arial, sans-serif; background:#f4f4f4; padding:20px;\">" +
            "<div style=\"max-width:600px;margin:auto;background:white;border-radius:10px;padding:24px;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">" +
            "<h2 style=\"color:#b91c1c;margin-top:0;\">⚠️ Alerta de posible fraude</h2>" +
            "<p style=\"color:#475569;font-size:14px;line-height:1.6;\">Se detectó un intento sospechoso en el bot de <strong>{{brandName}}</strong>.</p>" +
            "<table style=\"width:100%;border-collapse:collapse;font-size:13px;color:#1e293b;\">" +
            "<tr><td style=\"padding:6px 0;color:#64748b;width:130px;\">Cliente:</td><td style=\"padding:6px 0;\"><strong>{{clientName}}</strong></td></tr>" +
            "<tr><td style=\"padding:6px 0;color:#64748b;\">Severidad:</td><td style=\"padding:6px 0;\"><strong>{{severity}}</strong></td></tr>" +
            "<tr><td style=\"padding:6px 0;color:#64748b;\">Fecha:</td><td style=\"padding:6px 0;\">{{createdAt}}</td></tr>" +
            "<tr><td style=\"padding:6px 0;color:#64748b;\">Device:</td><td style=\"padding:6px 0;\">{{deviceOs}} · {{deviceBrowser}}</td></tr>" +
            "</table>" +
            "<div style=\"margin-top:20px;padding:14px;background:#fef2f2;border-left:4px solid #b91c1c;border-radius:6px;\">" +
            "<div style=\"font-size:11px;color:#991b1b;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:6px;\">Mensaje sospechoso</div>" +
            "<div style=\"color:#1e293b;font-size:14px;\">{{suspiciousMessage}}</div>" +
            "</div>" +
            "<div style=\"margin-top:16px;padding:14px;background:#fff7ed;border-left:4px solid #f59e0b;border-radius:6px;\">" +
            "<div style=\"font-size:11px;color:#92400e;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:6px;\">Motivo detectado</div>" +
            "<div style=\"color:#1e293b;font-size:14px;\">{{reason}}</div>" +
            "</div>" +
            "<p style=\"margin-top:22px;font-size:12px;color:#94a3b8;\">El bot ya cortó la conversación con el cliente. " +
            "Podés revisar el detalle completo desde el /admin.</p>" +
            "</div></body></html>"
        );
        e.setQuickAccessJson(
            "[" +
            "{\"id\":\"qa-1\",\"label\":\"📋 Mi reserva\",\"text\":\"Quiero consultar mi reserva\"}," +
            "{\"id\":\"qa-2\",\"label\":\"📄 Mis vouchers\",\"text\":\"Necesito mis vouchers\"}," +
            "{\"id\":\"qa-3\",\"label\":\"💳 Mis pagos\",\"text\":\"¿Cómo está mi plan de pagos?\"}," +
            "{\"id\":\"qa-4\",\"label\":\"✈️ Info de vuelo\",\"text\":\"¿Cuáles son los datos de mi vuelo?\"}" +
            "]"
        );
        e.setEnabledPanels("");   // sin paneles activos por default
        e.setPanelOrdersConfigJson(
            "{" +
            "\"currency\":\"ARS\"," +
            "\"currencySymbol\":\"$\"," +
            "\"orderNumberPrefix\":\"P-\"," +
            "\"allowDelivery\":true," +
            "\"allowPickup\":true," +
            "\"soundOnNewOrder\":true," +
            "\"statuses\":[" +
                "{\"key\":\"NEW\",\"label\":\"Nuevo\",\"color\":\"#2563eb\"}," +
                "{\"key\":\"CONFIRMED\",\"label\":\"Confirmado\",\"color\":\"#7c3aed\"}," +
                "{\"key\":\"IN_PREPARATION\",\"label\":\"En preparación\",\"color\":\"#f59e0b\"}," +
                "{\"key\":\"READY_FOR_PICKUP\",\"label\":\"Listo para retirar\",\"color\":\"#10b981\"}," +
                "{\"key\":\"DELIVERED\",\"label\":\"Entregado\",\"color\":\"#64748b\"}," +
                "{\"key\":\"CANCELLED\",\"label\":\"Cancelado\",\"color\":\"#ef4444\"}" +
            "]}"
        );
        e.setMenuEnabled(Boolean.FALSE);
        e.setMenuLabel("Menú");
        e.setMenuConfigJson(
            "{" +
            "\"style\":\"elegant\"," +
            "\"primaryColor\":\"#0f2756\"," +
            "\"accentColor\":\"#c9a961\"," +
            "\"tagline\":\"Nuestra propuesta\"," +
            "\"showSearch\":true," +
            "\"showCategoryTabs\":true," +
            "\"showFeatured\":true," +
            "\"allowOrdering\":true," +
            "\"orderButtonLabel\":\"Agregar al pedido\"," +
            "\"emptyStateText\":\"Pronto sumamos más opciones. ¡Contactanos!\"," +
            "\"itemsPerPage\":8," +
            "\"catalogId\":null," +
            "\"columnMapping\":{\"name\":\"nombre\",\"description\":\"descripcion\",\"price\":\"precio\",\"category\":\"categoria\",\"tags\":\"tags\",\"featured\":\"destacado\",\"imageName\":\"imagen\"}" +
            "}"
        );
        e.setLanguage("es");
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
        public String headerTitle;
        public String headerSubtitle;
        public String themeJson;
        public String welcomeMessage;

        public Boolean allowTts;
        public Boolean allowUserAudio;
        public Boolean useCustomVoice;
        public String  customVoiceId;

        public String  ttsModel;
        public Double  ttsStability;
        public Double  ttsSimilarity;
        public Double  ttsStyle;
        public Double  ttsSpeed;

        public String  customPrompt;
        public String  businessRulesJson;

        /** ID del BotPromptTemplate seleccionado. 0 o null → sin plantilla (usa customPrompt). */
        public Long    activePromptTemplateId;

        public Boolean allowReadReceipts;
        public Boolean allowShowImages;
        public Boolean allowSendEmails;

        public Boolean showQuickAccess;
        public String  quickAccessJson;

        public Integer conversationTimeoutMinutes;
        public String  dataRequestPrompt;

        public Boolean fraudDetectionEnabled;
        public String  fraudAlertEmails;
        public String  fraudEmailSubject;
        public String  fraudEmailTemplate;

        public String  enabledPanels;
        public String  panelOrdersConfigJson;

        public Boolean menuEnabled;
        public String  menuLabel;
        public String  menuConfigJson;

        public String  language;
        public String  savedVoicesJson;
        public String  websiteUrl;
        public Boolean websiteEnabled;

        public Instant updatedAt;
        public String  updatedBy;

        public static BotConfigDto fromEntity(BotConfig e) {
            BotConfigDto d = new BotConfigDto();
            d.botName                = e.getBotName();
            d.brandName              = e.getBrandName();
            d.logoUrl                = e.getLogoUrl();
            d.botAvatarUrl           = e.getBotAvatarUrl();
            d.headerTitle            = e.getHeaderTitle();
            d.headerSubtitle         = e.getHeaderSubtitle();
            d.themeJson              = e.getThemeJson();
            d.welcomeMessage         = e.getWelcomeMessage();
            d.allowTts               = e.getAllowTts();
            d.allowUserAudio         = e.getAllowUserAudio();
            d.useCustomVoice         = e.getUseCustomVoice();
            d.customVoiceId          = e.getCustomVoiceId();
            d.ttsModel               = e.getTtsModel();
            d.ttsStability           = e.getTtsStability();
            d.ttsSimilarity          = e.getTtsSimilarity();
            d.ttsStyle               = e.getTtsStyle();
            d.ttsSpeed               = e.getTtsSpeed();
            d.customPrompt           = e.getCustomPrompt();
            d.businessRulesJson      = e.getBusinessRulesJson();
            d.activePromptTemplateId = e.getActivePromptTemplateId();
            d.allowReadReceipts      = e.getAllowReadReceipts();
            d.allowShowImages        = e.getAllowShowImages();
            d.allowSendEmails        = e.getAllowSendEmails();
            d.showQuickAccess        = e.getShowQuickAccess();
            d.quickAccessJson        = e.getQuickAccessJson();
            d.conversationTimeoutMinutes = e.getConversationTimeoutMinutes();
            d.dataRequestPrompt      = e.getDataRequestPrompt();
            d.fraudDetectionEnabled  = e.getFraudDetectionEnabled();
            d.fraudAlertEmails       = e.getFraudAlertEmails();
            d.fraudEmailSubject      = e.getFraudEmailSubject();
            d.fraudEmailTemplate     = e.getFraudEmailTemplate();
            d.enabledPanels          = e.getEnabledPanels();
            d.panelOrdersConfigJson  = e.getPanelOrdersConfigJson();
            d.menuEnabled            = e.getMenuEnabled();
            d.menuLabel              = e.getMenuLabel();
            d.menuConfigJson         = e.getMenuConfigJson();
            d.language               = e.getLanguage();
            d.savedVoicesJson        = e.getSavedVoicesJson();
            d.websiteUrl             = e.getWebsiteUrl();
            d.websiteEnabled         = e.getWebsiteEnabled();
            d.updatedAt              = e.getUpdatedAt();
            d.updatedBy              = e.getUpdatedBy();
            return d;
        }
    }
}
