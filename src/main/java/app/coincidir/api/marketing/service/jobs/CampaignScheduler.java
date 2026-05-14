package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.marketing.service.MarketingCampaignService;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CampaignScheduler — Levanta campañas SCHEDULED cuya hora ya llegó y
 * las pasa a RUNNING, generando los CampaignRecipient correspondientes.
 *
 * Frecuencia: cada minuto. Buena granularidad para mensajes programados
 * sin abusar de la DB. La diferencia entre scheduled_at y la hora real
 * de envío puede ser de hasta 1 minuto, suficiente para campañas de
 * marketing.
 *
 * Lo que hace por cada campaña due:
 *   1. Resolver targets (segmento o filtro ad-hoc).
 *   2. Crear CampaignRecipient con whatsapp/email/push_status=PENDING
 *      para cada canal habilitado y aceptado por el cliente.
 *   3. Marcar la campaña como RUNNING y setear total_targeted.
 *
 * El envío real lo hace NotificationDispatcherJob (otro scheduler) que
 * procesa los PENDING. Esto desacopla el momento de "lanzar" del de
 * "entregar" — útil para campañas grandes que necesitan rate limiting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignScheduler {

    private final MarketingCampaignService campaignService;
    private final BotConfigRepository botConfigRepo;

    /** Cron: cada minuto en :00 segundos. */
    @Scheduled(cron = "0 * * * * *")
    public void run() {
        if (!isMarketingEnabled()) return;
        try {
            List<MarketingCampaign> due = campaignService.findDueScheduled(Instant.now());
            if (due.isEmpty()) {
                log.debug("[CampaignScheduler] tick — nada due");
                return;
            }
            for (MarketingCampaign c : due) {
                try {
                    campaignService.enqueueNow(c);
                    log.info("[CampaignScheduler] campaña #{} lanzada (scheduled_at={})",
                        c.getId(), c.getScheduledAt());
                } catch (Exception e) {
                    log.warn("[CampaignScheduler] error lanzando campaña #{}: {}",
                        c.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[CampaignScheduler] error en tick: {}", e.getMessage(), e);
        }
    }

    private boolean isMarketingEnabled() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        return cfg.isPresent() && Boolean.TRUE.equals(cfg.get().getMarketingEnabled());
    }
}
