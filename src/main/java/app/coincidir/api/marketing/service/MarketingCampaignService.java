package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.CampaignRecipient;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.marketing.domain.MarketingSegment;
import app.coincidir.api.marketing.repository.CampaignRecipientRepository;
import app.coincidir.api.marketing.repository.MarketingCampaignRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MarketingCampaignService — Ciclo de vida de una campaña.
 *
 * Estados:
 *   DRAFT       Recién creada, editable.
 *   SCHEDULED   Marcada para correr en scheduledAt (single shot).
 *   RUNNING     El scheduler la levantó, está enviando.
 *   COMPLETED   Todos los recipients fueron procesados.
 *   CANCELLED   El admin la canceló (solo desde DRAFT/SCHEDULED).
 *   FAILED      Error fatal durante el envío.
 *
 * Flujo de launch():
 *   1. Validar que esté en DRAFT o SCHEDULED.
 *   2. Resolver targets (segmentId guardado o targetFilterJson ad-hoc).
 *   3. Crear CampaignRecipient para cada target (dedup, no permite duplicar).
 *   4. Marcar status=RUNNING, started_at=now, total_targeted=N.
 *   5. Para cada recipient + canal habilitado → marca whatsapp_status=PENDING
 *      (etc). El envío real lo hace NotificationService llamado por el
 *      scheduler en otro tick.
 *
 * El servicio NO hace los envíos directos para evitar bloquear el thread
 * del admin que apretó "Enviar". Solo encola.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingCampaignService {

    private final MarketingCampaignRepository campaignRepo;
    private final CampaignRecipientRepository recipientRepo;
    private final MarketingSegmentService segmentService;

    @Autowired
    private ObjectMapper objectMapper;

    public Page<MarketingCampaign> list(Pageable pageable) {
        return campaignRepo.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Optional<MarketingCampaign> findById(Long id) {
        return campaignRepo.findById(id);
    }

    @Transactional
    public MarketingCampaign create(MarketingCampaign c) {
        if (c.getName() == null || c.getName().isBlank())
            throw new IllegalArgumentException("Nombre de campaña es requerido");
        if (c.getChannelsJson() == null || c.getChannelsJson().isBlank())
            throw new IllegalArgumentException("channels_json es requerido");
        if (c.getStatus() == null) c.setStatus(MarketingCampaign.Status.DRAFT);
        if (c.getScheduleType() == null) c.setScheduleType(MarketingCampaign.ScheduleType.IMMEDIATE);
        // Defaults defensivos: counters NOT NULL.
        if (c.getTotalTargeted() == null)  c.setTotalTargeted(0);
        if (c.getTotalSent() == null)      c.setTotalSent(0);
        if (c.getTotalDelivered() == null) c.setTotalDelivered(0);
        if (c.getTotalOpened() == null)    c.setTotalOpened(0);
        if (c.getTotalClicked() == null)   c.setTotalClicked(0);
        if (c.getTotalConverted() == null) c.setTotalConverted(0);
        return campaignRepo.save(c);
    }

    @Transactional
    public MarketingCampaign update(Long id, MarketingCampaign update) {
        MarketingCampaign c = campaignRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Campaña no encontrada"));
        if (c.getStatus() != MarketingCampaign.Status.DRAFT
            && c.getStatus() != MarketingCampaign.Status.SCHEDULED) {
            throw new IllegalStateException("No se puede editar una campaña en estado " + c.getStatus());
        }
        if (update.getName() != null)        c.setName(update.getName());
        if (update.getDescription() != null) c.setDescription(update.getDescription());
        if (update.getSegmentId() != null)   c.setSegmentId(update.getSegmentId());
        if (update.getTargetFilterJson() != null) c.setTargetFilterJson(update.getTargetFilterJson());
        if (update.getChannelsJson() != null) c.setChannelsJson(update.getChannelsJson());
        if (update.getMessageWhatsapp() != null) c.setMessageWhatsapp(update.getMessageWhatsapp());
        if (update.getMessageEmailSubject() != null) c.setMessageEmailSubject(update.getMessageEmailSubject());
        if (update.getMessageEmailBody() != null) c.setMessageEmailBody(update.getMessageEmailBody());
        if (update.getMessagePushTitle() != null) c.setMessagePushTitle(update.getMessagePushTitle());
        if (update.getMessagePushBody() != null) c.setMessagePushBody(update.getMessagePushBody());
        if (update.getCtaUrl() != null) c.setCtaUrl(update.getCtaUrl());
        if (update.getCouponId() != null) c.setCouponId(update.getCouponId());
        if (update.getScheduleType() != null) c.setScheduleType(update.getScheduleType());
        if (update.getScheduledAt() != null) c.setScheduledAt(update.getScheduledAt());
        if (update.getRecurrenceConfigJson() != null) c.setRecurrenceConfigJson(update.getRecurrenceConfigJson());
        if (update.getTriggerConfigJson() != null) c.setTriggerConfigJson(update.getTriggerConfigJson());
        return campaignRepo.save(c);
    }

    /**
     * Encolar la campaña: resolver destinatarios y marcar RUNNING.
     * Si schedule_type=IMMEDIATE arranca ahora; si SCHEDULED queda en
     * status=SCHEDULED para que la levante el job en scheduledAt.
     */
    @Transactional
    public MarketingCampaign launch(Long id) {
        MarketingCampaign c = campaignRepo.findById(id).orElseThrow();
        if (c.getStatus() != MarketingCampaign.Status.DRAFT) {
            throw new IllegalStateException("Solo se pueden lanzar campañas en DRAFT");
        }

        if (c.getScheduleType() == MarketingCampaign.ScheduleType.SCHEDULED) {
            if (c.getScheduledAt() == null || c.getScheduledAt().isBefore(Instant.now())) {
                throw new IllegalArgumentException("scheduledAt debe ser una fecha futura");
            }
            c.setStatus(MarketingCampaign.Status.SCHEDULED);
            return campaignRepo.save(c);
        }

        // IMMEDIATE / RECURRING / TRIGGERED → encolar ahora
        return enqueueNow(c);
    }

    /** Marca RUNNING y crea CampaignRecipient pendientes. */
    @Transactional
    public MarketingCampaign enqueueNow(MarketingCampaign c) {
        List<LoyaltyCustomer> targets = resolveTargets(c);
        Set<String> channels = parseChannels(c.getChannelsJson());

        int created = 0;
        for (LoyaltyCustomer cust : targets) {
            if (recipientRepo.findByCampaignIdAndCustomerId(c.getId(), cust.getId()).isPresent()) continue;
            CampaignRecipient r = new CampaignRecipient();
            r.setCampaignId(c.getId());
            r.setCustomerId(cust.getId());
            if (channels.contains("whatsapp") && Boolean.TRUE.equals(cust.getAcceptsWhatsapp()))
                r.setWhatsappStatus("PENDING");
            if (channels.contains("email") && Boolean.TRUE.equals(cust.getAcceptsEmail())
                && cust.getEmail() != null && !cust.getEmail().isBlank())
                r.setEmailStatus("PENDING");
            if (channels.contains("web_push") && Boolean.TRUE.equals(cust.getAcceptsPush())
                && cust.getWebPushSubscription() != null)
                r.setPushStatus("PENDING");
            recipientRepo.save(r);
            created++;
        }

        c.setStatus(MarketingCampaign.Status.RUNNING);
        c.setStartedAt(Instant.now());
        c.setTotalTargeted(created);
        return campaignRepo.save(c);
    }

    /** Marca la campaña COMPLETED. La invoca el scheduler tras procesar todos los recipients. */
    @Transactional
    public void markCompleted(Long campaignId) {
        MarketingCampaign c = campaignRepo.findById(campaignId).orElseThrow();
        c.setStatus(MarketingCampaign.Status.COMPLETED);
        c.setCompletedAt(Instant.now());
        campaignRepo.save(c);
    }

    @Transactional
    public void cancel(Long id) {
        MarketingCampaign c = campaignRepo.findById(id).orElseThrow();
        if (c.getStatus() != MarketingCampaign.Status.DRAFT
            && c.getStatus() != MarketingCampaign.Status.SCHEDULED) {
            throw new IllegalStateException("Solo se pueden cancelar campañas en DRAFT o SCHEDULED");
        }
        c.setStatus(MarketingCampaign.Status.CANCELLED);
        campaignRepo.save(c);
    }

    /** Resuelve la lista de clientes target: por segment_id o por target_filter_json ad-hoc. */
    private List<LoyaltyCustomer> resolveTargets(MarketingCampaign c) {
        if (c.getSegmentId() != null) {
            MarketingSegment seg = segmentService.findById(c.getSegmentId())
                .orElseThrow(() -> new IllegalArgumentException("Segmento no encontrado"));
            return segmentService.evaluate(seg);
        }
        if (c.getTargetFilterJson() != null && !c.getTargetFilterJson().isBlank()) {
            return segmentService.evaluateAdhoc(c.getTargetFilterJson());
        }
        throw new IllegalArgumentException("Campaña sin destinatarios: definí segment_id o target_filter_json");
    }

    private Set<String> parseChannels(String json) {
        Set<String> set = new HashSet<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) arr.forEach(n -> set.add(n.asText("").toLowerCase()));
        } catch (Exception ignored) {}
        return set;
    }

    public List<CampaignRecipient> recipientsForCampaign(Long campaignId, int max) {
        if (max <= 0) max = 100;
        return recipientRepo.findByCampaignId(campaignId).stream().limit(max).toList();
    }

    public List<CampaignRecipient> pendingForCampaign(Long campaignId) {
        return recipientRepo.findPendingInCampaign(campaignId);
    }

    public List<MarketingCampaign> findDueScheduled(Instant now) {
        return campaignRepo.findDueScheduled(now);
    }

    public Page<MarketingCampaign> listRecent(int n) {
        return campaignRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, n));
    }
}
