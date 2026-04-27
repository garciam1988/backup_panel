package app.coincidir.api.apiusage.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ApiUsageLog — registro de cada llamada a una API externa (Anthropic, OpenAI,
 * ElevenLabs, etc). Sirve para el dashboard de consumo y costos.
 *
 * El frontend reporta cada llamada vía POST /api/public/api-usage. El backend
 * calcula el costo en USD usando ApiPricing (tabla configurable) y lo guarda
 * para análisis posterior.
 *
 * Diseñado para ser extensible: cualquier API futura solo necesita un nuevo
 * provider + entradas en ApiPricing — la tabla no se modifica.
 *
 * Campos de uso varían por tipo de API:
 *   - LLM (Anthropic/OpenAI chat): input/output/cacheRead/cacheWrite tokens
 *   - Audio (Whisper transcription, ElevenLabs TTS): audio_seconds
 *   - Otras: cualquier campo aplicable; los no usados quedan en 0/null
 */
@Entity
@Table(name = "api_usage_log",
       indexes = {
           @Index(name = "idx_usage_created", columnList = "created_at"),
           @Index(name = "idx_usage_provider_created", columnList = "provider, created_at"),
           @Index(name = "idx_usage_session", columnList = "session_id"),
           @Index(name = "idx_usage_feature", columnList = "feature, created_at"),
       })
@Data
public class ApiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "anthropic" | "openai" | "elevenlabs" | otro futuro. */
    @Column(name = "provider", length = 40, nullable = false)
    private String provider;

    /** Modelo específico usado: "claude-sonnet-4-5", "gpt-4o-mini",
     *  "eleven_multilingual_v2", "whisper-1", etc. Nullable porque no
     *  todas las APIs tienen "modelo" como concepto. */
    @Column(name = "model", length = 80)
    private String model;

    /** Para LLMs: tokens de input "frescos" (no cacheados). */
    @Column(name = "input_tokens")
    private Integer inputTokens;

    /** Para LLMs: tokens de output. */
    @Column(name = "output_tokens")
    private Integer outputTokens;

    /** Para Anthropic prompt caching: tokens leídos del cache (0.1x precio). */
    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    /** Para Anthropic prompt caching: tokens escritos al cache (1.25x precio). */
    @Column(name = "cache_write_tokens")
    private Integer cacheWriteTokens;

    /** Para audio (Whisper STT, ElevenLabs TTS): segundos procesados. */
    @Column(name = "audio_seconds")
    private Integer audioSeconds;

    /** Para ElevenLabs y similares que cobran por caracteres en vez de tokens. */
    @Column(name = "characters")
    private Integer characters;

    /** Costo total calculado en USD. Se computa al insertar usando ApiPricing. */
    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    /** Feature de la app que originó la llamada. Permite breakdown por feature.
     *  Valores típicos: "chat" (conversación principal), "proactive_message",
     *  "extract_client_data", "transcribe_audio", "tts", "generate_prompt"
     *  (admin), etc. */
    @Column(name = "feature", length = 60)
    private String feature;

    /** Sesión del chat asociada (cuando aplica). Para correlacionar todo el
     *  consumo de una conversación específica. Null para llamadas no asociadas
     *  a un chat (ej: generación de prompt desde admin). */
    @Column(name = "session_id", length = 120)
    private String sessionId;

    /** Si hubo error en la llamada (frontend lo reporta igual para tracking). */
    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
