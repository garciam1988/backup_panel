package app.coincidir.api.apiusage.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ApiPricing — tabla de precios de las APIs externas, configurable desde
 * /admin (en la próxima entrega del dashboard). Usada por ApiUsageService
 * para calcular el costoUsd al insertar un ApiUsageLog.
 *
 * Una fila por (provider, model). Si el modelo no matchea, fallback a la
 * fila con model=null del mismo provider. Si tampoco, costo=0 con warning.
 *
 * Precios siempre en USD, expresados POR MILLÓN de unidades:
 *   - Para LLMs: por millón de tokens (estándar de la industria)
 *   - Para audio: por minuto (audioUsdPerMin) — convertido internamente
 *   - Para caracteres: por millón de caracteres (charUsdPerM)
 *
 * Las filas iniciales se seedean desde DataInitializer al primer arranque.
 */
@Entity
@Table(name = "api_pricing",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_pricing_provider_model", columnNames = {"provider", "model"})
       })
@Data
public class ApiPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", length = 40, nullable = false)
    private String provider;

    /** "claude-sonnet-4-5", "gpt-4o-mini", "eleven_multilingual_v2", etc.
     *  Nullable: si es null, es el "default" del provider (fallback). */
    @Column(name = "model", length = 80)
    private String model;

    /** Etiqueta amigable para mostrar en el admin. Ej: "Claude Sonnet 4.5". */
    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "input_usd_per_m", precision = 12, scale = 6)
    private BigDecimal inputUsdPerM;

    @Column(name = "output_usd_per_m", precision = 12, scale = 6)
    private BigDecimal outputUsdPerM;

    /** Cache reads = 10% del input price (Anthropic). */
    @Column(name = "cache_read_usd_per_m", precision = 12, scale = 6)
    private BigDecimal cacheReadUsdPerM;

    /** Cache writes = 125% del input price (Anthropic, TTL 5min). */
    @Column(name = "cache_write_usd_per_m", precision = 12, scale = 6)
    private BigDecimal cacheWriteUsdPerM;

    /** Para APIs de audio que cobran por minuto (Whisper). */
    @Column(name = "audio_usd_per_min", precision = 12, scale = 6)
    private BigDecimal audioUsdPerMin;

    /** Para APIs que cobran por caracteres (ElevenLabs, ~$0.30 por millón
     *  de caracteres en plan starter al momento de escribir esto). */
    @Column(name = "char_usd_per_m", precision = 12, scale = 6)
    private BigDecimal charUsdPerM;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() { updatedAt = Instant.now(); }
}
