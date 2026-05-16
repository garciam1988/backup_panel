package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ManagerConfig — singleton de configuración del módulo /manager (ARViz / Jarvis).
 *
 * Análogo a {@link BotConfig} pero scopeado al manager interno. Se accede desde:
 *   - GET  /api/admin/manager-config  → AdminPanel (sección ManagerSection.jsx)
 *   - PUT  /api/admin/manager-config  → AdminPanel
 *   - GET  /api/manager/config        → ManagerApp.jsx (requiere JWT con manager_access)
 *
 * Importante: este config NO se expone públicamente. El /manager requiere login
 * con un user_account que tenga manager_access = true.
 *
 * Convención de IDs: siempre ID=1 como singleton (igual que bot_config).
 */
@Entity
@Table(name = "manager_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ManagerConfig {

    @Id
    private Long id;

    // ── Identidad / branding ─────────────────────────────────────────────────
    @Column(name = "display_name", length = 80)
    private String displayName;          // "JARVIS", "ARViz", "LETY"

    @Column(name = "wake_word", length = 40)
    private String wakeWord;             // "jarvis" (siempre lowercase)

    @Column(name = "tagline", length = 200)
    private String tagline;

    @Column(name = "theme_color", length = 16)
    private String themeColor;           // "#00d4ff"

    @Column(name = "accent_color", length = 16)
    private String accentColor;

    // ── Modelo y prompt ──────────────────────────────────────────────────────
    @Column(name = "llm_model", length = 80)
    private String llmModel;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    // ── Voz (TTS) ────────────────────────────────────────────────────────────
    @Column(name = "tts_enabled", nullable = false)
    @Builder.Default
    private boolean ttsEnabled = true;

    @Column(name = "tts_provider", length = 20)
    private String ttsProvider;          // "elevenlabs" | "openai" | "browser"

    @Column(name = "elevenlabs_voice_id", length = 128)
    private String elevenlabsVoiceId;

    @Column(name = "elevenlabs_model", length = 80)
    private String elevenlabsModel;

    @Column(name = "tts_stability")
    private Double ttsStability;

    @Column(name = "tts_similarity")
    private Double ttsSimilarity;

    @Column(name = "tts_speed")
    private Double ttsSpeed;

    @Column(name = "openai_tts_voice", length = 40)
    private String openaiTtsVoice;

    // ── Wake word / STT ──────────────────────────────────────────────────────
    @Column(name = "wake_word_enabled", nullable = false)
    @Builder.Default
    private boolean wakeWordEnabled = true;

    @Column(name = "stt_language", length = 16)
    private String sttLanguage;

    @Column(name = "silence_timeout_ms")
    private Integer silenceTimeoutMs;

    // ── Tools whitelist ──────────────────────────────────────────────────────
    @Column(name = "tools_whitelist_json", columnDefinition = "LONGTEXT")
    private String toolsWhitelistJson;

    // ── UI / Behavior ────────────────────────────────────────────────────────
    @Column(name = "max_popups")
    private Integer maxPopups;

    @Column(name = "auto_close_popups_minutes")
    private Integer autoClosePopupsMinutes;

    @Column(name = "quick_commands_json", columnDefinition = "TEXT")
    private String quickCommandsJson;

    // ── Logging ──────────────────────────────────────────────────────────────
    @Column(name = "log_conversations", nullable = false)
    @Builder.Default
    private boolean logConversations = true;

    // ── Auditoría ────────────────────────────────────────────────────────────
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
