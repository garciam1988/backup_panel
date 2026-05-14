package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.CampaignRecipient;
import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.marketing.domain.NotificationLog;
import app.coincidir.api.marketing.repository.CampaignRecipientRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.MarketingCampaignRepository;
import app.coincidir.api.marketing.service.CouponService;
import app.coincidir.api.marketing.service.MarketingCampaignService;
import app.coincidir.api.marketing.service.NotificationService;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NotificationDispatcherJob — Procesa los CampaignRecipient en estado
 * PENDING y manda los mensajes por cada canal habilitado.
 *
 * Frecuencia: cada 30 segundos. Cuando hay una campaña recién lanzada
 * con muchos targets, esto permite que el envío empiece casi enseguida.
 *
 * Flujo:
 *   1. Buscar campañas en RUNNING con recipients PENDING.
 *   2. Para cada recipient (batch limitado para no inundar providers):
 *      a. Renderizar mensaje sustituyendo {firstName}, {lastName}, {couponCode}.
 *      b. Para cada canal con status=PENDING: llamar a NotificationService.
 *      c. Actualizar el status del canal a SENT (o FAILED si tiró error).
 *      d. Actualizar contadores de la campaña.
 *   3. Si la campaña ya no tiene PENDING, marcarla COMPLETED.
 *
 * Rate limiting básico: máximo 50 recipients procesados por tick. Si la
 * campaña tiene 500 targets, va a tardar ~5 minutos en completarse (50 *
 * cada 30s = 100/min). Esto evita pegarle muy fuerte a Twilio/SMTP y
 * cubre el caso de campañas grandes sin tirar la app.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcherJob {

    private final MarketingCampaignRepository campaignRepo;
    private final CampaignRecipientRepository recipientRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final NotificationService notificationService;
    private final MarketingCampaignService campaignService;
    private final CouponService couponService;
    private final BotConfigRepository botConfigRepo;

    /** Tope de recipients procesados por tick. */
    private static final int BATCH_LIMIT = 50;

    /** Cron: cada 30 segundos. */
    @Scheduled(cron = "*/30 * * * * *")
    public void run() {
        if (!isMarketingEnabled()) return;
        try {
            List<MarketingCampaign> running = campaignRepo.findByStatus(MarketingCampaign.Status.RUNNING);
            for (MarketingCampaign c : running) {
                try { processCampaign(c); }
                catch (Exception e) {
                    log.warn("[NotificationDispatcher] error en campaña #{}: {}",
                        c.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[NotificationDispatcher] error en tick: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void processCampaign(MarketingCampaign campaign) {
        List<CampaignRecipient> pending = recipientRepo.findPendingInCampaign(campaign.getId());
        if (pending.isEmpty()) {
            campaignService.markCompleted(campaign.getId());
            log.info("[NotificationDispatcher] campaña #{} COMPLETED (sin recipients pendientes)",
                campaign.getId());
            return;
        }

        int batchSize = Math.min(BATCH_LIMIT, pending.size());
        int sent = 0;
        int failed = 0;
        Coupon couponEntity = resolveCouponEntity(campaign.getCouponId());
        String couponCode = couponEntity == null ? null : couponEntity.getCode();

        for (int i = 0; i < batchSize; i++) {
            CampaignRecipient r = pending.get(i);
            Optional<LoyaltyCustomer> custOpt = customerRepo.findById(r.getCustomerId());
            if (custOpt.isEmpty()) {
                markAllFailed(r, "Cliente no encontrado");
                failed++;
                continue;
            }
            LoyaltyCustomer customer = custOpt.get();
            Map<String, Object> vars = buildTemplateVars(customer, couponCode, campaign);
            String sourceRef = "campaign:" + campaign.getId();

            boolean anySent = false;
            boolean anyFailed = false;

            // ── WhatsApp ──
            if ("PENDING".equals(r.getWhatsappStatus())
                && campaign.getMessageWhatsapp() != null && !campaign.getMessageWhatsapp().isBlank()) {
                try {
                    String body = notificationService.renderTemplate(campaign.getMessageWhatsapp(), vars);
                    NotificationLog n = notificationService.queueWhatsapp(
                        NotificationLog.SourceType.CAMPAIGN, sourceRef, customer.getId(), body);
                    if (n == null) {
                        // Customer sin teléfono, sin consentimiento, o no encontrado
                        r.setWhatsappStatus("SKIPPED");
                    } else if (n.getStatus() == NotificationLog.Status.SENT) {
                        r.setWhatsappStatus("SENT");
                        r.setWhatsappSentAt(Instant.now());
                        anySent = true;
                    } else if (n.getStatus() == NotificationLog.Status.FAILED) {
                        r.setWhatsappStatus("FAILED");
                        if (n.getErrorMessage() != null) r.setErrorMessage("WhatsApp: " + n.getErrorMessage());
                        anyFailed = true;
                    } else {
                        // QUEUED (Twilio no configurado, por ejemplo)
                        r.setWhatsappStatus("QUEUED");
                    }
                } catch (Exception e) {
                    r.setWhatsappStatus("FAILED");
                    r.setErrorMessage("WhatsApp: " + e.getMessage());
                    anyFailed = true;
                }
            }

            // ── Email ──
            if ("PENDING".equals(r.getEmailStatus())
                && campaign.getMessageEmailSubject() != null && campaign.getMessageEmailBody() != null) {
                try {
                    String subject = notificationService.renderTemplate(campaign.getMessageEmailSubject(), vars);
                    String htmlBody = notificationService.renderTemplate(campaign.getMessageEmailBody(), vars);
                    // Las campañas siempre van con el template profesional (header de marca,
                    // CTA, caja de cupón). El sendEmail simple queda para usos transaccionales.
                    NotificationLog n = notificationService.sendEmailWithTemplate(
                        NotificationLog.SourceType.CAMPAIGN, sourceRef, customer.getId(),
                        subject, htmlBody, campaign.getCtaUrl(), couponEntity);
                    r.setEmailStatus(n != null && n.getStatus() == NotificationLog.Status.SENT ? "SENT" : "FAILED");
                    r.setEmailSentAt(Instant.now());
                    if (n != null && n.getStatus() == NotificationLog.Status.SENT) anySent = true;
                    else if (n != null && n.getStatus() == NotificationLog.Status.FAILED) anyFailed = true;
                } catch (Exception e) {
                    r.setEmailStatus("FAILED");
                    r.setErrorMessage("Email: " + e.getMessage());
                    anyFailed = true;
                }
            }

            // ── Web Push ──
            if ("PENDING".equals(r.getPushStatus())
                && campaign.getMessagePushTitle() != null && campaign.getMessagePushBody() != null) {
                try {
                    String title = notificationService.renderTemplate(campaign.getMessagePushTitle(), vars);
                    String body = notificationService.renderTemplate(campaign.getMessagePushBody(), vars);
                    NotificationLog n = notificationService.queueWebPush(
                        NotificationLog.SourceType.CAMPAIGN, sourceRef, customer.getId(),
                        title, body, campaign.getCtaUrl());
                    if (n == null) {
                        // Sin subscription, sin consentimiento, o no encontrado
                        r.setPushStatus("SKIPPED");
                    } else if (n.getStatus() == NotificationLog.Status.SENT) {
                        r.setPushStatus("SENT");
                        r.setPushSentAt(Instant.now());
                        anySent = true;
                    } else if (n.getStatus() == NotificationLog.Status.FAILED) {
                        r.setPushStatus("FAILED");
                        if (n.getErrorMessage() != null) r.setErrorMessage("Push: " + n.getErrorMessage());
                        anyFailed = true;
                    } else {
                        r.setPushStatus("QUEUED");
                    }
                } catch (Exception e) {
                    r.setPushStatus("FAILED");
                    r.setErrorMessage("Push: " + e.getMessage());
                    anyFailed = true;
                }
            }

            recipientRepo.save(r);
            if (anySent) sent++;
            if (anyFailed) failed++;
        }

        // Actualizar contadores de la campaña
        campaign.setTotalSent(campaign.getTotalSent() + sent);
        campaignRepo.save(campaign);

        log.info("[NotificationDispatcher] campaña #{} → {} enviados, {} fallidos (quedan {} PENDING)",
            campaign.getId(), sent, failed, Math.max(0, pending.size() - batchSize));

        // Si ya no quedan pending, marcar completed en el próximo tick
        if (pending.size() <= batchSize) {
            campaignService.markCompleted(campaign.getId());
            log.info("[NotificationDispatcher] campaña #{} COMPLETED tras procesar todos los recipients",
                campaign.getId());
        }
    }

    private Map<String, Object> buildTemplateVars(LoyaltyCustomer customer, String couponCode, MarketingCampaign campaign) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", customer.getFirstName() == null ? "" : customer.getFirstName());
        vars.put("lastName", customer.getLastName() == null ? "" : customer.getLastName());
        vars.put("fullName", buildFullName(customer));
        vars.put("phone", customer.getPhone() == null ? "" : customer.getPhone());
        vars.put("email", customer.getEmail() == null ? "" : customer.getEmail());
        vars.put("couponCode", couponCode == null ? "" : couponCode);
        vars.put("campaignName", campaign.getName() == null ? "" : campaign.getName());
        vars.put("ctaUrl", campaign.getCtaUrl() == null ? "" : campaign.getCtaUrl());
        return vars;
    }

    private String buildFullName(LoyaltyCustomer c) {
        String first = c.getFirstName() == null ? "" : c.getFirstName();
        String last = c.getLastName() == null ? "" : c.getLastName();
        return (first + " " + last).trim();
    }

    private Coupon resolveCouponEntity(Long couponId) {
        if (couponId == null) return null;
        return couponService.findById(couponId).orElse(null);
    }

    private void markAllFailed(CampaignRecipient r, String reason) {
        if ("PENDING".equals(r.getWhatsappStatus())) r.setWhatsappStatus("FAILED");
        if ("PENDING".equals(r.getEmailStatus()))    r.setEmailStatus("FAILED");
        if ("PENDING".equals(r.getPushStatus()))     r.setPushStatus("FAILED");
        r.setErrorMessage(reason);
        recipientRepo.save(r);
    }

    private boolean isMarketingEnabled() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        return cfg.isPresent() && Boolean.TRUE.equals(cfg.get().getMarketingEnabled());
    }
}
