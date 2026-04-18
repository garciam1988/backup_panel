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

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    // ── Audio ─────────────────────────────────────────────────
    @Column(name = "allow_tts", nullable = false)
    private Boolean allowTts = true;

    @Column(name = "allow_user_audio", nullable = false)
    private Boolean allowUserAudio = true;

    @Column(name = "use_custom_voice", nullable = false)
    private Boolean useCustomVoice = false;

    @Column(name = "custom_voice_id", length = 128)
    private String customVoiceId;

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
