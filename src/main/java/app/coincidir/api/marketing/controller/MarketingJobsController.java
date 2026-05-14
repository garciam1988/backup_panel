package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.service.jobs.BirthdayBonusJob;
import app.coincidir.api.marketing.service.jobs.CampaignScheduler;
import app.coincidir.api.marketing.service.jobs.MigrateBotClientsJob;
import app.coincidir.api.marketing.service.jobs.NotificationDispatcherJob;
import app.coincidir.api.marketing.service.jobs.PointsExpiryJob;
import app.coincidir.api.marketing.service.jobs.RedemptionExpiryJob;
import app.coincidir.api.marketing.service.jobs.TriggeredCampaignJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MarketingJobsController — Disparo manual de los jobs del módulo desde el
 * panel admin. Útil para QA (no esperar al cron), demos, y para que el admin
 * pueda forzar un envío sin esperar al próximo tick.
 *
 * Cada endpoint corre el job y devuelve un resumen del resultado. NO bloquea
 * (los jobs son razonablemente rápidos para volúmenes esperados; si en
 * algún momento son lentos, conviene volverlos async).
 *
 * Todas las rutas requieren JWT panel.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing/jobs")
@RequiredArgsConstructor
public class MarketingJobsController {

    private final RedemptionExpiryJob redemptionExpiryJob;
    private final PointsExpiryJob pointsExpiryJob;
    private final CampaignScheduler campaignScheduler;
    private final NotificationDispatcherJob notificationDispatcherJob;
    private final TriggeredCampaignJob triggeredCampaignJob;
    private final BirthdayBonusJob birthdayBonusJob;
    private final MigrateBotClientsJob migrateBotClientsJob;

    @PostMapping("/expire-redemptions")
    public ResponseEntity<Map<String, Object>> expireRedemptions() {
        return runAndReport("expire-redemptions", redemptionExpiryJob::run);
    }

    @PostMapping("/expire-points")
    public ResponseEntity<Map<String, Object>> expirePoints() {
        return runAndReport("expire-points", pointsExpiryJob::run);
    }

    @PostMapping("/run-campaign-scheduler")
    public ResponseEntity<Map<String, Object>> runCampaignScheduler() {
        return runAndReport("campaign-scheduler", campaignScheduler::run);
    }

    @PostMapping("/dispatch-notifications")
    public ResponseEntity<Map<String, Object>> dispatchNotifications() {
        return runAndReport("dispatch-notifications", notificationDispatcherJob::run);
    }

    @PostMapping("/run-triggered-campaigns")
    public ResponseEntity<Map<String, Object>> runTriggeredCampaigns() {
        return runAndReport("triggered-campaigns", triggeredCampaignJob::run);
    }

    @PostMapping("/run-birthday-bonus")
    public ResponseEntity<Map<String, Object>> runBirthdayBonus() {
        return runAndReport("birthday-bonus", birthdayBonusJob::run);
    }

    /**
     * Migración inicial one-shot: recorre los BotTableRecord de las tablas
     * con phoneColumn configurado y crea/reactiva un loyalty_customer por cada
     * cliente histórico. Idempotente — puede correrse múltiples veces.
     *
     * Importante: usa source="migration" en los enrolamientos, lo que evita
     * disparar welcome bonus para clientes históricos (que reservaron hace
     * meses y no son "nuevos").
     */
    @PostMapping("/migrate-bot-clients")
    public ResponseEntity<Map<String, Object>> migrateBotClients() {
        Instant start = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", "migrate-bot-clients");
        result.put("startedAt", start.toString());
        try {
            Map<String, Object> jobResult = migrateBotClientsJob.run();
            result.put("ok", true);
            result.putAll(jobResult);
            log.info("[MarketingJobsController] migrate-bot-clients OK: {}", jobResult);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            result.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());
            log.warn("[MarketingJobsController] migrate-bot-clients falló: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(result);
        }
    }

    /** Helper común: ejecuta el job, mide tiempo y devuelve un summary. */
    private ResponseEntity<Map<String, Object>> runAndReport(String jobName, Runnable job) {
        Instant start = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", jobName);
        result.put("startedAt", start.toString());
        try {
            job.run();
            result.put("ok", true);
            result.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());
            log.info("[MarketingJobsController] {} ejecutado manualmente ({}ms)", jobName, result.get("durationMs"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            result.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());
            log.warn("[MarketingJobsController] {} falló: {}", jobName, e.getMessage(), e);
            return ResponseEntity.status(500).body(result);
        }
    }
}
