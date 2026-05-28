package app.coincidir.api.service.backups;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * ReservasBackupScheduler — dispara el backup de reservas según el intervalo
 * configurado en BD (reservas_backup_config.interval_minutes), editable desde
 * el AdminPanel sin redeploy.
 *
 * Patrón: igual que BackupsScheduler (el backup SQL diario), hacemos un tick
 * fijo cada 60s y decidimos en código si ya pasó el intervalo desde la última
 * corrida. Esto permite cambiar el intervalo en runtime — algo que un
 * @Scheduled(fixedDelay) fijo no permite.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservasBackupScheduler {

    private final ReservasBackupService service;

    /** Tick cada 60s; el intervalo real lo decide la config. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        try {
            ReservasBackupConfig cfg = service.getConfig();
            if (!Boolean.TRUE.equals(cfg.getEnabled())) return;

            int intervalMin = cfg.getIntervalMinutes() != null && cfg.getIntervalMinutes() > 0
                    ? cfg.getIntervalMinutes() : 15;

            Instant last = cfg.getLastRunAt();
            // Si nunca corrió, corre ahora. Si pasó el intervalo desde la
            // última corrida, corre. Si no, espera al próximo tick.
            if (last != null) {
                long minsSince = Duration.between(last, Instant.now()).toMinutes();
                if (minsSince < intervalMin) return;
            }
            service.runBackup(false);
        } catch (Exception ex) {
            // El tick nunca debe romper el scheduler global.
            log.warn("[ReservasBackup] tick falló: {}", ex.getMessage());
        }
    }
}
