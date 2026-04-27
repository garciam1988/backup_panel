package app.coincidir.api.apiusage.service;

import app.coincidir.api.apiusage.domain.ApiPricing;
import app.coincidir.api.apiusage.domain.ApiUsageLog;
import app.coincidir.api.apiusage.repository.ApiPricingRepository;
import app.coincidir.api.apiusage.repository.ApiUsageLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * ApiUsageService — corazón del módulo de tracking. Responsabilidades:
 *
 *   - Calcular el costo USD de una llamada usando la tabla ApiPricing.
 *   - Persistir el ApiUsageLog con el costo ya calculado.
 *   - Seedear precios iniciales al primer arranque (idempotente).
 *
 * Separar costo + persistencia simplifica el frontend: solo manda los
 * tokens/audio/chars, y el backend resuelve el resto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiUsageService {

    private final ApiUsageLogRepository logRepo;
    private final ApiPricingRepository pricingRepo;

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal SIXTY = new BigDecimal("60");

    /**
     * Persiste un ApiUsageLog calculando el costo automáticamente.
     * Si el provider/model no tiene precio configurado, guarda costoUsd=0
     * y deja un warning en los logs (se puede ajustar después editando
     * los precios y recomputando, pero no es crítico para el dashboard).
     */
    @Transactional
    public ApiUsageLog recordUsage(ApiUsageLog input) {
        if (input.getProvider() == null || input.getProvider().isBlank()) {
            throw new IllegalArgumentException("provider requerido");
        }
        // Normalizar nulls a 0 para sumas posteriores cómodas
        if (input.getInputTokens() == null) input.setInputTokens(0);
        if (input.getOutputTokens() == null) input.setOutputTokens(0);
        if (input.getCacheReadTokens() == null) input.setCacheReadTokens(0);
        if (input.getCacheWriteTokens() == null) input.setCacheWriteTokens(0);
        if (input.getAudioSeconds() == null) input.setAudioSeconds(0);
        if (input.getCharacters() == null) input.setCharacters(0);

        BigDecimal cost = computeCost(input);
        input.setCostUsd(cost);

        return logRepo.save(input);
    }

    /**
     * Calcula el costo en USD de una llamada usando la tabla ApiPricing.
     * Soporta:
     *   - Tokens (input, output, cache_read, cache_write)
     *   - Audio en segundos (Whisper)
     *   - Caracteres (ElevenLabs)
     *
     * Para encontrar el precio: busca primero (provider, model). Si no,
     * fallback a (provider, null) — el "default" del provider. Si tampoco,
     * devuelve 0.
     */
    public BigDecimal computeCost(ApiUsageLog log) {
        ApiPricing p = findPricing(log.getProvider(), log.getModel());
        if (p == null) {
            // Nota: NO podemos usar el logger acá porque el parámetro se llama 'log'
            // y haría shadowing. Logueamos vía System.err o reemplazamos silenciosamente.
            org.slf4j.LoggerFactory.getLogger(ApiUsageService.class)
                .warn("[ApiUsage] sin pricing para provider={} model={} (cost=0)",
                      log.getProvider(), log.getModel());
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;

        if (log.getInputTokens() > 0 && p.getInputUsdPerM() != null) {
            total = total.add(p.getInputUsdPerM()
                    .multiply(BigDecimal.valueOf(log.getInputTokens()))
                    .divide(MILLION, 8, RoundingMode.HALF_UP));
        }
        if (log.getOutputTokens() > 0 && p.getOutputUsdPerM() != null) {
            total = total.add(p.getOutputUsdPerM()
                    .multiply(BigDecimal.valueOf(log.getOutputTokens()))
                    .divide(MILLION, 8, RoundingMode.HALF_UP));
        }
        if (log.getCacheReadTokens() > 0 && p.getCacheReadUsdPerM() != null) {
            total = total.add(p.getCacheReadUsdPerM()
                    .multiply(BigDecimal.valueOf(log.getCacheReadTokens()))
                    .divide(MILLION, 8, RoundingMode.HALF_UP));
        }
        if (log.getCacheWriteTokens() > 0 && p.getCacheWriteUsdPerM() != null) {
            total = total.add(p.getCacheWriteUsdPerM()
                    .multiply(BigDecimal.valueOf(log.getCacheWriteTokens()))
                    .divide(MILLION, 8, RoundingMode.HALF_UP));
        }
        if (log.getAudioSeconds() > 0 && p.getAudioUsdPerMin() != null) {
            // segundos → minutos → costo
            total = total.add(p.getAudioUsdPerMin()
                    .multiply(BigDecimal.valueOf(log.getAudioSeconds()))
                    .divide(SIXTY, 8, RoundingMode.HALF_UP));
        }
        if (log.getCharacters() > 0 && p.getCharUsdPerM() != null) {
            total = total.add(p.getCharUsdPerM()
                    .multiply(BigDecimal.valueOf(log.getCharacters()))
                    .divide(MILLION, 8, RoundingMode.HALF_UP));
        }

        // Redondeo final a 6 decimales (suficiente para fracciones de centavo)
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    private ApiPricing findPricing(String provider, String model) {
        if (model != null && !model.isBlank()) {
            Optional<ApiPricing> exact = pricingRepo.findByProviderAndModel(provider, model);
            if (exact.isPresent()) return exact.get();
        }
        return pricingRepo.findByProviderAndModelIsNull(provider).orElse(null);
    }

    /**
     * Seedeo de precios iniciales al primer arranque. Idempotente: solo
     * inserta filas que NO existen ya (no pisa precios editados por el admin).
     *
     * Precios de referencia (USD/millón de tokens, ajustar al momento de uso):
     *   - Anthropic Claude Sonnet 4.5: $3 input / $15 output
     *   - Anthropic Claude Haiku 4.5:  $1 input / $5 output
     *   - OpenAI GPT-4o-mini:          $0.15 input / $0.60 output
     *   - OpenAI Whisper-1:            $0.006 / minuto de audio
     *   - ElevenLabs:                  ~$0.30 / millón de caracteres (varía por plan)
     */
    @PostConstruct
    @Transactional
    public void seedInitialPricing() {
        try {
            seed("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5",
                 "3.00", "15.00", "0.30", "3.75", null, null);
            seed("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku 4.5",
                 "1.00", "5.00", "0.10", "1.25", null, null);
            seed("anthropic", "claude-sonnet-4-20250514", "Claude Sonnet 4 (legacy)",
                 "3.00", "15.00", "0.30", "3.75", null, null);
            // Default Anthropic: si aparece un model nuevo y no está cargado,
            // usamos esto como fallback. Mantenemos precio Sonnet (más comun).
            seed("anthropic", null, "Anthropic (default)",
                 "3.00", "15.00", "0.30", "3.75", null, null);

            seed("openai", "gpt-4o-mini", "GPT-4o-mini",
                 "0.15", "0.60", null, null, null, null);
            seed("openai", "whisper-1", "Whisper STT",
                 null, null, null, null, "0.006", null);
            seed("openai", "tts-1", "OpenAI TTS",
                 null, null, null, null, null, "15.00");
            seed("openai", null, "OpenAI (default)",
                 "0.15", "0.60", null, null, null, null);

            seed("elevenlabs", "eleven_multilingual_v2", "ElevenLabs Multilingual v2",
                 null, null, null, null, null, "0.30");
            seed("elevenlabs", null, "ElevenLabs (default)",
                 null, null, null, null, null, "0.30");
        } catch (Exception e) {
            log.warn("[ApiUsage] error en seed inicial de pricing: {}", e.getMessage());
        }
    }

    private void seed(String provider, String model, String label,
                      String inUsdPerM, String outUsdPerM,
                      String cacheReadUsdPerM, String cacheWriteUsdPerM,
                      String audioUsdPerMin, String charUsdPerM) {
        // Idempotente: si ya existe la fila, no la pisamos
        boolean exists = (model == null)
                ? pricingRepo.findByProviderAndModelIsNull(provider).isPresent()
                : pricingRepo.findByProviderAndModel(provider, model).isPresent();
        if (exists) return;

        ApiPricing p = new ApiPricing();
        p.setProvider(provider);
        p.setModel(model);
        p.setLabel(label);
        if (inUsdPerM != null) p.setInputUsdPerM(new BigDecimal(inUsdPerM));
        if (outUsdPerM != null) p.setOutputUsdPerM(new BigDecimal(outUsdPerM));
        if (cacheReadUsdPerM != null) p.setCacheReadUsdPerM(new BigDecimal(cacheReadUsdPerM));
        if (cacheWriteUsdPerM != null) p.setCacheWriteUsdPerM(new BigDecimal(cacheWriteUsdPerM));
        if (audioUsdPerMin != null) p.setAudioUsdPerMin(new BigDecimal(audioUsdPerMin));
        if (charUsdPerM != null) p.setCharUsdPerM(new BigDecimal(charUsdPerM));
        p.setActive(true);
        pricingRepo.save(p);
        log.info("[ApiUsage] seedeado pricing: {}/{}", provider, model == null ? "(default)" : model);
    }
}
