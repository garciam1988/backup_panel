package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.marketing.service.EarnBonusService;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * BirthdayBonusJob — Job diario que busca clientes de cumpleaños y les
 * aplica reglas con trigger="birthday".
 *
 * Frecuencia: una vez al día a las 09:00 hora de Argentina (configurable
 * con cron). Como el clock de Spring usa UTC, ajustamos al horario local:
 *   09:00 ART = 12:00 UTC
 *
 * Idempotencia: el job NO mantiene su propio control de "ya corrió hoy".
 * Confía en que el cron lo invoca 1 sola vez por día. Si por alguna razón
 * se invoca dos veces (manual desde el endpoint admin, por ejemplo), va a
 * crear DOS transacciones de earn_bonus para el cumple. Mitigación: en una
 * iteración futura podemos sumar un "birthday_year_applied" en customer
 * para deduplicar.
 *
 * El job NO falla si Marketing está deshabilitado o si no hay reglas
 * birthday: simplemente skip-ea.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirthdayBonusJob {

    private final EarnBonusService earnBonusService;
    private final LoyaltyProgramService programService;

    /** Una vez por día a las 12:00 UTC (≈ 09:00 ART). */
    @Scheduled(cron = "0 0 12 * * *")
    public void runScheduled() {
        run();
    }

    /**
     * Punto de entrada manual (desde MarketingJobsController para que el
     * admin pueda dispararlo desde la UI).
     */
    public JobResult run() {
        Instant started = Instant.now();
        try {
            if (!programService.getActiveProgram().getActive()) {
                log.debug("BirthdayBonusJob: programa inactivo, skip");
                return new JobResult(true, 0, Duration.between(started, Instant.now()).toMillis(), null);
            }
            int processed = earnBonusService.runBirthdayBonusJob();
            long ms = Duration.between(started, Instant.now()).toMillis();
            log.info("BirthdayBonusJob OK: {} cumpleañeros, {}ms", processed, ms);
            return new JobResult(true, processed, ms, null);
        } catch (Exception e) {
            long ms = Duration.between(started, Instant.now()).toMillis();
            log.error("BirthdayBonusJob falló: {}", e.getMessage(), e);
            return new JobResult(false, 0, ms, e.getMessage());
        }
    }

    public record JobResult(boolean ok, int processed, long durationMs, String error) {}
}
