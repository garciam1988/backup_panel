package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * PublicBotConfigController — lectura PÚBLICA de la config del bot.
 *
 * Lo consumen visitantes anónimos del bot sin token. Antes devolvía el
 * BotConfigDto completo del admin, lo que exponía campos sensibles que
 * el frontend del bot NO necesita (config de fraude, panel admin, etc.).
 * Eso lo convertía en una superficie de ataque: cualquiera con curl podía
 * leerse las reglas internas, los emails de alerta de fraude y la
 * estructura del panel admin.
 *
 * Ahora devuelve un DTO MINIMIZADO con solo los campos públicos por
 * naturaleza (lo visual + lo operativo del chat).
 *
 * SI NECESITÁS EXPONER UN CAMPO NUEVO ACÁ: pensá dos veces si el bot
 * realmente lo necesita en runtime, o si puede hacerse via:
 *   - El template de prompt (que ya se baja por separado por id).
 *   - Un endpoint específico (ej: /api/public/menu-images).
 *   - Sin frontend: lógica de servidor en el endpoint de chat.
 *
 *   GET /api/public/bot-config → PublicBotConfigDto (subset filtrado)
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-config")
@RequiredArgsConstructor
public class PublicBotConfigController {

    private static final Long SINGLETON_ID = 1L;

    private final BotConfigRepository repo;

    @GetMapping
    public PublicBotConfigDto get() {
        BotConfig entity = repo.findById(SINGLETON_ID).orElseThrow(() -> {
            log.warn("[public/bot-config] No existe la config singleton (id=1).");
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "bot_config no inicializada");
        });
        return PublicBotConfigDto.fromEntity(entity);
    }

    /**
     * DTO público SLIM. Solo incluye campos que el frontend del bot necesita
     * en runtime para renderizar la UI y construir el system prompt.
     *
     * EXCLUIDOS (vs. admin DTO):
     *   - fraudAlertEmails       — phishing target potencial
     *   - fraudEmailSubject      — info interna de alerta
     *   - fraudEmailTemplate     — revela qué eventos disparan alertas
     *   - fraudDetectionEnabled  — info interna; la detección la hace backend
     *   - panelOrdersConfigJson  — config del panel admin, no del bot
     *
     * INCLUIDOS pero a revisar a futuro (los necesita el bot, pero exponen
     * información del negocio):
     *   - customPrompt: usado como fallback si no hay activePromptTemplate.
     *     Si todos los bots tienen template activo (el flujo recomendado),
     *     este campo puede vaciarse acá. Hoy lo dejamos para no romper bots
     *     legacy.
     *   - businessRulesJson: reglas internas del negocio inyectadas al prompt.
     *     Idealmente esto debería vivir dentro del template activo, no acá.
     *   - dataRequestPrompt: prompt para pedir datos al cliente. Idem.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicBotConfigDto {
        // ── Identidad visual del chat ───────────────────────────────────
        public String  botName;
        public String  brandName;
        public String  logoUrl;
        public String  botAvatarUrl;
        public String  headerTitle;
        public String  headerSubtitle;
        public String  themeJson;
        public String  welcomeMessage;

        // ── Contacto (lo que el cliente puede usar para llamar/escribir) ──
        public String  contactWhatsapp;
        public String  contactPhone;
        public String  contactAddress;

        // ── Audio / TTS / STT (config visible al usuario para grabar) ────
        public Boolean allowTts;
        public Boolean allowUserAudio;
        public Boolean useCustomVoice;
        public String  customVoiceId;
        public String  ttsModel;
        public Double  ttsStability;
        public Double  ttsSimilarity;
        public Double  ttsStyle;
        public Double  ttsSpeed;
        public String  savedVoicesJson;

        // ── Prompt y reglas (necesarias para construir el system prompt) ──
        // Estos campos SÍ se exponen porque el bot los necesita para
        // dialogar con Claude. Si en el futuro se mueve toda la config
        // de prompt al template (vía activePromptTemplateId), se pueden
        // omitir acá.
        public String  customPrompt;
        public String  businessRulesJson;
        public String  dataRequestPrompt;
        public Long    activePromptTemplateId;

        // ── Permisos de UX (controlan lo que el bot puede mostrar/hacer) ──
        public Boolean allowReadReceipts;
        public Boolean allowShowImages;
        public Boolean allowSendEmails;

        // ── Quick access (botones rápidos en el chat) ────────────────────
        public Boolean showQuickAccess;
        public String  quickAccessJson;

        // ── Conversación ────────────────────────────────────────────────
        public Integer conversationTimeoutMinutes;

        // ── Paneles habilitados (define qué secciones del bot mostrar) ──
        public String  enabledPanels;

        // ── Menú (si tiene catálogo de productos visible al cliente) ─────
        public Boolean menuEnabled;
        public String  menuLabel;
        public String  menuConfigJson;

        // ── Vouchers (config de comprobantes visibles al cliente) ────────
        public Boolean vouchersEnabled;
        public String  voucherApiBaseUrl;

        // ── i18n ────────────────────────────────────────────────────────
        public String  language;

        // ── Redes sociales (botones públicos visibles al cliente) ────────
        public String  websiteUrl;
        public Boolean websiteEnabled;
        public String  instagramHandle;
        public Boolean instagramEnabled;
        public String  facebookHandle;
        public Boolean facebookEnabled;

        // ── Metadata pública (útil para el cache del frontend) ───────────
        public Instant updatedAt;

        // EXPLÍCITAMENTE NO INCLUIDO:
        //   - fraudAlertEmails        (privacidad: emails internos)
        //   - fraudEmailSubject       (no necesario en frontend)
        //   - fraudEmailTemplate      (no necesario en frontend)
        //   - fraudDetectionEnabled   (lógica de servidor)
        //   - panelOrdersConfigJson   (config del panel admin)
        //   - updatedBy               (usuario interno del admin)

        public static PublicBotConfigDto fromEntity(BotConfig e) {
            PublicBotConfigDto d = new PublicBotConfigDto();
            d.botName                = e.getBotName();
            d.brandName              = e.getBrandName();
            d.logoUrl                = e.getLogoUrl();
            d.botAvatarUrl           = e.getBotAvatarUrl();
            d.headerTitle            = e.getHeaderTitle();
            d.headerSubtitle         = e.getHeaderSubtitle();
            d.themeJson              = e.getThemeJson();
            d.welcomeMessage         = e.getWelcomeMessage();
            d.contactWhatsapp        = e.getContactWhatsapp();
            d.contactPhone           = e.getContactPhone();
            d.contactAddress         = e.getContactAddress();
            d.allowTts               = e.getAllowTts();
            d.allowUserAudio         = e.getAllowUserAudio();
            d.useCustomVoice         = e.getUseCustomVoice();
            d.customVoiceId          = e.getCustomVoiceId();
            d.ttsModel               = e.getTtsModel();
            d.ttsStability           = e.getTtsStability();
            d.ttsSimilarity          = e.getTtsSimilarity();
            d.ttsStyle               = e.getTtsStyle();
            d.ttsSpeed               = e.getTtsSpeed();
            d.savedVoicesJson        = e.getSavedVoicesJson();
            d.customPrompt           = e.getCustomPrompt();
            d.businessRulesJson      = e.getBusinessRulesJson();
            d.dataRequestPrompt      = e.getDataRequestPrompt();
            d.activePromptTemplateId = e.getActivePromptTemplateId();
            d.allowReadReceipts      = e.getAllowReadReceipts();
            d.allowShowImages        = e.getAllowShowImages();
            d.allowSendEmails        = e.getAllowSendEmails();
            d.showQuickAccess        = e.getShowQuickAccess();
            d.quickAccessJson        = e.getQuickAccessJson();
            d.conversationTimeoutMinutes = e.getConversationTimeoutMinutes();
            d.enabledPanels          = e.getEnabledPanels();
            d.menuEnabled            = e.getMenuEnabled();
            d.menuLabel              = e.getMenuLabel();
            d.menuConfigJson         = e.getMenuConfigJson();
            d.vouchersEnabled        = e.getVouchersEnabled();
            d.voucherApiBaseUrl      = e.getVoucherApiBaseUrl();
            d.language               = e.getLanguage();
            d.websiteUrl             = e.getWebsiteUrl();
            d.websiteEnabled         = e.getWebsiteEnabled();
            d.instagramHandle        = e.getInstagramHandle();
            d.instagramEnabled       = e.getInstagramEnabled();
            d.facebookHandle         = e.getFacebookHandle();
            d.facebookEnabled        = e.getFacebookEnabled();
            d.updatedAt              = e.getUpdatedAt();
            return d;
        }
    }
}
