package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.CampaignRecipient;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.marketing.repository.CampaignRecipientRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.MarketingCampaignRepository;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TriggeredCampaignJob — Dispara campañas TRIGGERED por eventos
 * automáticos (cumpleaños, inactividad, welcome, etc).
 *
 * Frecuencia: diaria a las 09:00 AM (hora cómoda para que los mensajes
 * salgan en horario razonable, no a las 3 AM).
 *
 * Tipos de trigger soportados (trigger_config_json):
 *
 *   1. BIRTHDAY
 *      { "event": "birthday", "daysBefore": 7 }
 *      Apunta a clientes que cumplen años en los próximos N días.
 *      "daysBefore": 0 = el día del cumple; > 0 = N días antes.
 *
 *   2. INACTIVITY
 *      { "event": "inactivity", "daysInactive": 30 }
 *      Clientes con last_activity_at más viejo que N días.
 *
 *   3. ENROLLMENT_ANNIVERSARY (futuro)
 *      { "event": "enrollment_anniversary", "daysAfter": 365 }
 *      Aniversario del enrolamiento (mismo mes/día N años después).
 *
 * Para evitar duplicados: si ya existe un CampaignRecipient para
 * (campaign_id, customer_id) en los últimos N días (depende del trigger),
 * skip. Esto cubre que el job se vuelva a correr al día siguiente y no
 * mande otra vez el mismo mensaje al mismo cliente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggeredCampaignJob {

    private final MarketingCampaignRepository campaignRepo;
    private final CampaignRecipientRepository recipientRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final BotConfigRepository botConfigRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cron: diario a las 09:00 AM. */
    @Scheduled(cron = "0 0 9 * * *")
    public void run() {
        if (!isMarketingEnabled()) return;
        try {
            List<MarketingCampaign> all = campaignRepo.findByStatus(MarketingCampaign.Status.RUNNING);
            List<MarketingCampaign> triggered = all.stream()
                .filter(c -> c.getScheduleType() == MarketingCampaign.ScheduleType.TRIGGERED)
                .filter(c -> c.getTriggerConfigJson() != null && !c.getTriggerConfigJson().isBlank())
                .toList();

            if (triggered.isEmpty()) {
                log.debug("[TriggeredCampaignJob] tick — sin campañas TRIGGERED activas");
                return;
            }

            for (MarketingCampaign c : triggered) {
                try { processCampaign(c); }
                catch (Exception e) {
                    log.warn("[TriggeredCampaignJob] error en campaña #{}: {}",
                        c.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[TriggeredCampaignJob] error en tick: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void processCampaign(MarketingCampaign campaign) {
        JsonNode trig;
        try {
            trig = objectMapper.readTree(campaign.getTriggerConfigJson());
        } catch (Exception e) {
            log.warn("[TriggeredCampaignJob] trigger_config_json inválido en #{}: {}",
                campaign.getId(), e.getMessage());
            return;
        }

        String event = trig.path("event").asText("").toLowerCase();
        List<LoyaltyCustomer> matches = switch (event) {
            case "birthday"     -> findBirthdayTargets(trig);
            case "inactivity"   -> findInactiveTargets(trig);
            case "enrollment_anniversary" -> findAnniversaryTargets(trig);
            default -> List.of();
        };

        if (matches.isEmpty()) {
            log.debug("[TriggeredCampaignJob] campaña #{} ({}) — sin matches hoy",
                campaign.getId(), event);
            return;
        }

        // Filtrar duplicados: clientes ya targeteados en los últimos N días
        Set<Long> alreadyTargeted = recentRecipientsOf(campaign.getId(), dedupeWindowDays(event));
        Set<String> channels = parseChannels(campaign.getChannelsJson());

        int created = 0;
        for (LoyaltyCustomer customer : matches) {
            if (alreadyTargeted.contains(customer.getId())) continue;
            CampaignRecipient r = new CampaignRecipient();
            r.setCampaignId(campaign.getId());
            r.setCustomerId(customer.getId());
            if (channels.contains("whatsapp") && Boolean.TRUE.equals(customer.getAcceptsWhatsapp()))
                r.setWhatsappStatus("PENDING");
            if (channels.contains("email") && Boolean.TRUE.equals(customer.getAcceptsEmail())
                && customer.getEmail() != null && !customer.getEmail().isBlank())
                r.setEmailStatus("PENDING");
            if (channels.contains("web_push") && Boolean.TRUE.equals(customer.getAcceptsPush())
                && customer.getWebPushSubscription() != null)
                r.setPushStatus("PENDING");
            recipientRepo.save(r);
            created++;
        }

        if (created > 0) {
            campaign.setTotalTargeted(campaign.getTotalTargeted() + created);
            campaignRepo.save(campaign);
            log.info("[TriggeredCampaignJob] campaña #{} ({}) → {} nuevos recipients",
                campaign.getId(), event, created);
        }
    }

    private List<LoyaltyCustomer> findBirthdayTargets(JsonNode trig) {
        int daysBefore = trig.path("daysBefore").asInt(0);
        return customerRepo.findBirthdaysWithinNextDays(daysBefore);
    }

    private List<LoyaltyCustomer> findInactiveTargets(JsonNode trig) {
        int daysInactive = trig.path("daysInactive").asInt(30);
        Instant cutoff = Instant.now().minus(daysInactive, ChronoUnit.DAYS);
        return customerRepo.findInactiveSince(cutoff);
    }

    private List<LoyaltyCustomer> findAnniversaryTargets(JsonNode trig) {
        // Trigger útil más adelante. Por ahora simple: mismo mes-día que enrolled_at hoy.
        MonthDay today = MonthDay.now();
        return customerRepo.findAll().stream()
            .filter(c -> c.getEnrolledAt() != null)
            .filter(c -> Boolean.TRUE.equals(c.getActive()))
            .filter(c -> c.getDeletedAt() == null)
            .filter(c -> {
                LocalDate d = c.getEnrolledAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                return MonthDay.from(d).equals(today);
            })
            .toList();
    }

    /**
     * Cuántos días hacia atrás considerar para deduplicar. Birthday se
     * dispara una vez por año (365), inactividad cada 30 días, anniversary
     * cada 365 también.
     */
    private int dedupeWindowDays(String event) {
        return switch (event) {
            case "birthday", "enrollment_anniversary" -> 300;
            case "inactivity" -> 30;
            default -> 7;
        };
    }

    /**
     * Devuelve los customer_id que ya fueron targeteados por esta campaña
     * en los últimos N días, para deduplicar.
     */
    private Set<Long> recentRecipientsOf(Long campaignId, int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Set<Long> ids = new HashSet<>();
        for (CampaignRecipient r : recipientRepo.findByCampaignId(campaignId)) {
            if (r.getCreatedAt() != null && r.getCreatedAt().isAfter(cutoff)) {
                ids.add(r.getCustomerId());
            }
        }
        return ids;
    }

    private Set<String> parseChannels(String json) {
        Set<String> set = new HashSet<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) arr.forEach(n -> set.add(n.asText("").toLowerCase()));
        } catch (Exception ignored) {}
        return set;
    }

    private boolean isMarketingEnabled() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        return cfg.isPresent() && Boolean.TRUE.equals(cfg.get().getMarketingEnabled());
    }
}
