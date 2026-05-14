package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RedemptionExpiryJob — Marca como EXPIRED los canjes PENDING cuya
 * expires_at ya pasó, y revierte los deltas (devuelve estampillas/puntos/
 * cashback al cliente).
 *
 * Corre cada 10 minutos. Eso da granularidad suficiente: si un canje
 * vence a las 14:00, queda marcado EXPIRED entre 14:00 y 14:10, y el
 * cliente recupera sus estampillas en ese rango.
 *
 * Idempotente: si no hay canjes vencidos, no hace nada. Solo opera si
 * bot_config.marketing_enabled = true (apaga el job si el módulo está
 * deshabilitado).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedemptionExpiryJob {

    private final LoyaltyRedemptionService redemptionService;
    private final BotConfigRepository botConfigRepo;

    /** Cron: cada 10 minutos en :00, :10, :20, :30, :40, :50 */
    @Scheduled(cron = "0 0/10 * * * *")
    public void run() {
        if (!isMarketingEnabled()) return;
        try {
            int expired = redemptionService.expirePending();
            if (expired > 0) {
                log.info("[RedemptionExpiryJob] {} redemptions expiradas y revertidas", expired);
            } else {
                log.debug("[RedemptionExpiryJob] tick — nada que expirar");
            }
        } catch (Exception e) {
            log.warn("[RedemptionExpiryJob] error en tick: {}", e.getMessage(), e);
        }
    }

    private boolean isMarketingEnabled() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        return cfg.isPresent() && Boolean.TRUE.equals(cfg.get().getMarketingEnabled());
    }
}
