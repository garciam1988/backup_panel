package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotConfig — Configuración del bot CoinBot (Lety).
 *
 * Tabla singleton: siempre existe UNA sola fila con id=1. El AdminPanel la edita
 * y el bot (web) la lee al montar.
 *
 * Estrategia híbrida:
 *  - Flags y strings cortas → columnas tipadas
 *  - Arrays complejos (reglas, accesos rápidos) → columnas TEXT con JSON adentro
 *  - Prompt completo y welcome → columnas TEXT (pueden ser largos)
 */
@Entity
@Table(name = "bot_config")
@Getter @Setter
public class BotConfig {

    /** Singleton: siempre id=1 */
    @Id
    private Long id;

    // ── Identidad ─────────────────────────────────────────────────
    @Column(name = "bot_name", length = 100)
    private String botName;

    @Column(name = "brand_name", length = 100)
    private String brandName;

    /** URL o data:image base64. Puede ser largo (base64 de un logo). */
    @Column(name = "logo_url", columnDefinition = "LONGTEXT")
    private String logoUrl;

    /** Avatar del bot (cara/foto). URL o data:image base64. Si está vacío, el front usa el ícono del avión. */
    @Column(name = "bot_avatar_url", columnDefinition = "LONGTEXT")
    private String botAvatarUrl;

    /** Título del header del chat (ej: "Asistencia Leonel"). Si está vacío, el front cae a "Asistencia {botName}". */
    @Column(name = "header_title", length = 255)
    private String headerTitle;

    /** Subtítulo del header (ej: "Disponible ahora · Soporte post venta"). Si está vacío, el front usa el default. */
    @Column(name = "header_subtitle", length = 255)
    private String headerSubtitle;

    /** Tema de colores (JSON). 6 colores: primary, primaryDark, primaryLight, chatBg, botBubble, botBubbleText.
     *  Si es NULL o inválido, el frontend cae a los colores default de Coincidir (azul). */
    @Column(name = "theme_json", columnDefinition = "TEXT")
    private String themeJson;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    // ── Contacto del local ────────────────────────────────────
    // Datos de contacto alternativo del cliente. Se muestran en la pantalla
    // de mantenimiento del bot (cuando el backend está caído) para que el
    // visitante pueda contactar al local por otra vía. También están
    // disponibles para que el bot los use en su prompt.
    // Todos opcionales — si están vacíos, no se muestra esa sección.
    @Column(name = "contact_whatsapp", length = 40)
    private String contactWhatsapp;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "contact_address", length = 255)
    private String contactAddress;

    // ── Audio ─────────────────────────────────────────────────
    @Column(name = "allow_tts", nullable = false)
    private Boolean allowTts = true;

    @Column(name = "allow_user_audio", nullable = false)
    private Boolean allowUserAudio = true;

    @Column(name = "use_custom_voice", nullable = false)
    private Boolean useCustomVoice = false;

    @Column(name = "custom_voice_id", length = 128)
    private String customVoiceId;

    // ── Ajustes del TTS (ElevenLabs) ─────────────────────────────────────
    /** Modelo: eleven_flash_v2_5 (default), eleven_turbo_v2_5, eleven_multilingual_v2, eleven_v3 */
    @Column(name = "tts_model", length = 80)
    private String ttsModel;

    /** 0.0-1.0. Más bajo = más expresivo/variable. */
    @Column(name = "tts_stability")
    private Double ttsStability;

    /** 0.0-1.0. Cuánto se parece a la voz original. */
    @Column(name = "tts_similarity")
    private Double ttsSimilarity;

    /** 0.0-1.0. Exageración prosódica/emocional. */
    @Column(name = "tts_style")
    private Double ttsStyle;

    /** 0.7-1.2. Velocidad de la voz. */
    @Column(name = "tts_speed")
    private Double ttsSpeed;

    // ── Prompt ─────────────────────────────────────────────────
    /** Si está vacío o NULL → el frontend usa ORIGINAL_PROMPT */
    @Column(name = "custom_prompt", columnDefinition = "LONGTEXT")
    private String customPrompt;

    /**
     * ID del BotPromptTemplate seleccionado por el admin (si hay uno).
     * Si está seteado, el bot usa el prompt_text de ese template (prioridad sobre customPrompt).
     * Si es NULL, se cae a customPrompt (y si eso también está vacío, al ORIGINAL_PROMPT del frontend).
     */
    @Column(name = "active_prompt_template_id")
    private Long activePromptTemplateId;

    /** JSON array: [{"id":"r-1","enabled":true,"text":"..."}] */
    @Column(name = "business_rules_json", columnDefinition = "TEXT")
    private String businessRulesJson;

    // ── Permisos ─────────────────────────────────────────────────
    @Column(name = "allow_read_receipts", nullable = false)
    private Boolean allowReadReceipts = true;

    @Column(name = "allow_show_images", nullable = false)
    private Boolean allowShowImages = true;

    @Column(name = "allow_send_emails", nullable = false)
    private Boolean allowSendEmails = true;

    // ── Accesos rápidos ─────────────────────────────────────────────────
    @Column(name = "show_quick_access", nullable = false)
    private Boolean showQuickAccess = true;

    /** JSON array: [{"id":"qa-1","label":"📋 Mi reserva","text":"Quiero..."}] */
    @Column(name = "quick_access_json", columnDefinition = "TEXT")
    private String quickAccessJson;

    // ── Logging de conversaciones ─────────────────────────────────────────
    /** Minutos de inactividad para considerar la charla cerrada y persistirla. Default 15. */
    @Column(name = "conversation_timeout_minutes", nullable = false)
    private Integer conversationTimeoutMinutes = 15;

    // ── Detección de fraude ──────────────────────────────────────────────
    /** Si está activada, el bot clasifica cada mensaje del cliente y alerta si detecta fraude. */
    @Column(name = "fraud_detection_enabled", nullable = false)
    private Boolean fraudDetectionEnabled = Boolean.FALSE;

    /** Emails destino separados por coma. */
    @Column(name = "fraud_alert_emails", length = 500)
    private String fraudAlertEmails;

    /** Asunto del mail de alerta (admite {{variables}}). */
    @Column(name = "fraud_email_subject", length = 300)
    private String fraudEmailSubject;

    /** HTML de la plantilla del mail (admite {{variables}}). */
    @Column(name = "fraud_email_template", columnDefinition = "LONGTEXT")
    private String fraudEmailTemplate;

    // ── Paneles externos (/panel) ────────────────────────────────────────
    /** CSV de paneles activos. Por ahora solo "orders". */
    @Column(name = "enabled_panels", length = 300)
    private String enabledPanels;

    /** Config JSON específica para el panel de pedidos (moneda, estados, etc). */
    @Column(name = "panel_orders_config_json", columnDefinition = "TEXT")
    private String panelOrdersConfigJson;

    // ── Menú digital (carta / catálogo visual) ──────────────────────────
    @Column(name = "menu_enabled", nullable = false)
    private Boolean menuEnabled = Boolean.FALSE;

    /** Label que aparece en el FAB del bot. "Menú" / "Carta" / "Productos" / "Servicios". */
    @Column(name = "menu_label", length = 60)
    private String menuLabel;

    /** Config JSON del menú: style, columnas de Excel a mapear, categorías, etc. */
    @Column(name = "menu_config_json", columnDefinition = "TEXT")
    private String menuConfigJson;

    // ── Vouchers (post-venta YES Travel y similares) ────────────────────
    /**
     * Si true, expone al bot las tools nativas de vouchers:
     *   - obtener_vouchers: lista los servicios EMITIDOS del pasajero
     *   - enviar_voucher_email: envía un voucher PDF al email del pasajero
     *   - descargar_voucher: descarga el voucher PDF en el navegador
     *
     * Estas tools NO son SQL ni HTTP genéricas: dependen de los endpoints
     * /api/coinbot/voucher-* que arman el PDF client-side (VoucherRenderer).
     * Para que funcionen, el bot debe estar deployado contra un cliente que
     * implemente esos endpoints (hoy: YES Travel / Coincidir).
     *
     * Default: false (otros bots como ARViz no lo necesitan).
     */
    @Column(name = "vouchers_enabled", nullable = false)
    private Boolean vouchersEnabled = Boolean.FALSE;

    /**
     * URL base del admin panel del cliente que expone /api/admin/groups/{id}/vouchers.
     * Si está vacía o null, CoinBotController cae al valor configurado en
     * application.yml (coincidir.admin-panel-url, controlable vía env ADMIN_PANEL_URL).
     *
     * Configurable desde /admin (sección "Permisos del bot" → "Tools nativas de vouchers").
     * Permite que distintos bots apunten a backends de vouchers distintos sin redeployar.
     *
     * Ej: "https://admin.yes-traveluy.com" (sin trailing slash).
     */
    @Column(name = "voucher_api_base_url", length = 500)
    private String voucherApiBaseUrl;

    // ── Idioma del bot ──────────────────────────────────────────────────
    // Códigos ISO: "es" (Español, default), "en" (English), "pt-BR" (Português Brasil).
    // Afecta: textos de UI del bot, idioma en que Claude responde, y auto-traducción
    // de descripciones de productos del Excel.
    @Column(name = "language", length = 8)
    private String language;

    /**
     * Biblioteca de voces guardadas (ElevenLabs). JSON array de objetos:
     * [{"id":"uuid","name":"Laura","voiceId":"21m00Tcm...","createdAt":"..."}]
     * El admin puede agregar/eliminar; la voz activa sigue siendo customVoiceId.
     */
    @Column(name = "saved_voices_json", columnDefinition = "TEXT")
    private String savedVoicesJson;

    /**
     * URL del sitio web oficial del bot/marca. Se guarda siempre que el admin
     * la cargue (aún con el toggle de uso en false). Si websiteEnabled=true,
     * se inyecta al system prompt para que el bot pueda sugerirla/usarla.
     */
    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    /** Si es true, el bot es consciente del sitio y puede derivar al cliente. */
    @Column(name = "website_enabled")
    private Boolean websiteEnabled;

    /** Handle o URL de Instagram. Se guarda como lo cargó el admin; se normaliza al mostrar. */
    @Column(name = "instagram_handle", length = 200)
    private String instagramHandle;

    /** Si es true, el bot sabe del Instagram y puede derivar/mencionarlo. */
    @Column(name = "instagram_enabled")
    private Boolean instagramEnabled;

    /** Handle o URL de Facebook (página/perfil). */
    @Column(name = "facebook_handle", length = 200)
    private String facebookHandle;

    /** Si es true, el bot sabe del Facebook y puede derivar/mencionarlo. */
    @Column(name = "facebook_enabled")
    private Boolean facebookEnabled;

    /**
     * Prompt libre que le dice al bot qué datos debe pedirle al usuario
     * al iniciar la charla (nombre, apellido, DNI, email, teléfono, etc.).
     * Se inyecta al system prompt del bot. Configurable por bot/marca desde /admin.
     */
    @Column(name = "data_request_prompt", columnDefinition = "TEXT")
    private String dataRequestPrompt;

    // ── Módulo Marketing (Loyalty + Campañas) ─────────────────────
    /**
     * Si es true, el módulo Marketing está activo en este deploy. Cuando es
     * false, los endpoints /api/admin/marketing/** y /api/public/loyalty/**
     * siguen existiendo pero el panel admin oculta la sección y el bot no
     * expone tools de loyalty.
     */
    @Column(name = "marketing_enabled")
    private Boolean marketingEnabled;

    /**
     * Configuración JSON del módulo Marketing. Forma esperada:
     * {
     *   "programId": 1,
     *   "exposedTools": ["get_loyalty_status","enroll_customer","list_rewards",
     *                    "redeem_reward","get_active_campaigns","get_active_coupons"],
     *   "pwaBaseUrl": "https://loyalty.cliente.com",
     *   "enrollment": { "askBirthdate": true, "askEmail": true,
     *                   "consentText": "Acepto recibir promos por WhatsApp" },
     *   "channels": {
     *     "whatsapp": { "enabled": true, "twilioFromNumber": "+5491155555555" },
     *     "email":    { "enabled": true },
     *     "webpush":  { "enabled": true, "vapidPublicKey": "..." }
     *   }
     * }
     */
    @Column(name = "marketing_config_json", columnDefinition = "LONGTEXT")
    private String marketingConfigJson;

    // ── Auditoría ─────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Email del admin que hizo el último update (para auditoría liviana). */
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        if (id == null) id = 1L; // fuerza singleton
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
