package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.repository.ApiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * ApiCallLogCleanupJob — limpia logs viejos una vez por día.
 *
 * Retención configurable via 'coincidir.api-call-log.retention-days' (default: 30).
 * Si es 0 o negativo, el job no hace nada (retención infinita).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCallLogCleanupJob {

    private final ApiCallLogRepository repo;

    @Value("${coincidir.api-call-log.retention-days:30}")
    private int retentionDays;

    /** Todos los días a las 3:30 AM. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanup() {
        if (retentionDays <= 0) {
            log.info("[ApiCallLog] cleanup deshabilitado (retentionDays={})", retentionDays);
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = repo.deleteOlderThan(cutoff);
        log.info("[ApiCallLog] cleanup: {} logs borrados (anteriores a {})", deleted, cutoff);
    }
}
