package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingSegment;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.MarketingSegmentRepository;
import app.coincidir.api.marketing.util.SegmentEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MarketingSegmentService — CRUD de segmentos + evaluación contra población.
 *
 * Estrategia: traemos TODOS los clientes activos en memoria y los evaluamos
 * con SegmentEvaluator. Es lo más simple y suficiente para volúmenes
 * esperados (decenas de miles por tenant). Si en algún momento un tenant
 * crece a 100k+, se puede portar criteria_json → JPA Specification.
 *
 * Cache: cada vez que se ejecuta evaluate() actualizamos estimated_size +
 * last_computed_at del segmento. Eso permite al panel admin mostrar
 * "12 clientes en este segmento (actualizado hace 5min)" sin re-evaluar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingSegmentService {

    private final MarketingSegmentRepository segmentRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyCardRepository cardRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private SegmentEvaluator evaluator() {
        return new SegmentEvaluator(objectMapper);
    }

    public List<MarketingSegment> listActive() {
        return segmentRepo.findByDeletedAtIsNullAndActiveTrueOrderByNameAsc();
    }

    public List<MarketingSegment> listAll() {
        return segmentRepo.findByDeletedAtIsNullOrderByNameAsc();
    }

    public Optional<MarketingSegment> findById(Long id) {
        return segmentRepo.findById(id).filter(s -> s.getDeletedAt() == null);
    }

    @Transactional
    public MarketingSegment create(MarketingSegment s) {
        if (s.getName() == null || s.getName().isBlank())
            throw new IllegalArgumentException("Nombre del segmento es requerido");
        if (s.getCriteriaJson() == null || s.getCriteriaJson().isBlank())
            throw new IllegalArgumentException("criteria_json es requerido");
        validateCriteriaJson(s.getCriteriaJson());
        return segmentRepo.save(s);
    }

    @Transactional
    public MarketingSegment update(Long id, MarketingSegment update) {
        MarketingSegment s = findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Segmento no encontrado: " + id));
        if (update.getName() != null) s.setName(update.getName());
        if (update.getDescription() != null) s.setDescription(update.getDescription());
        if (update.getCriteriaJson() != null) {
            validateCriteriaJson(update.getCriteriaJson());
            s.setCriteriaJson(update.getCriteriaJson());
        }
        if (update.getActive() != null) s.setActive(update.getActive());
        return segmentRepo.save(s);
    }

    @Transactional
    public void softDelete(Long id) {
        MarketingSegment s = findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Segmento no encontrado: " + id));
        s.setDeletedAt(Instant.now());
        s.setActive(false);
        segmentRepo.save(s);
    }

    private void validateCriteriaJson(String json) {
        try { objectMapper.readTree(json); }
        catch (Exception e) { throw new IllegalArgumentException("criteria_json inválido: " + e.getMessage()); }
    }

    /**
     * Evalúa el segmento sobre la población actual. Devuelve los matches y
     * actualiza la cache.
     */
    @Transactional
    public List<LoyaltyCustomer> evaluate(MarketingSegment segment) {
        List<LoyaltyCustomer> all = customerRepo.findAll();
        Map<Long, LoyaltyCard> cardsByCustomer = new HashMap<>();
        cardRepo.findAll().forEach(c -> cardsByCustomer.put(c.getCustomerId(), c));

        SegmentEvaluator ev = evaluator();
        List<LoyaltyCustomer> matches = new ArrayList<>();
        for (LoyaltyCustomer c : all) {
            if (c.getDeletedAt() != null || !Boolean.TRUE.equals(c.getActive())) continue;
            if (ev.matches(segment.getCriteriaJson(), c, cardsByCustomer.get(c.getId()))) {
                matches.add(c);
            }
        }

        segment.setEstimatedSize(matches.size());
        segment.setLastComputedAt(Instant.now());
        segmentRepo.save(segment);
        log.debug("Segmento {} evaluado: {} matches", segment.getId(), matches.size());
        return matches;
    }

    /** Evalúa un criteria_json ad-hoc (sin crear el segmento). */
    public List<LoyaltyCustomer> evaluateAdhoc(String criteriaJson) {
        validateCriteriaJson(criteriaJson);
        List<LoyaltyCustomer> all = customerRepo.findAll();
        Map<Long, LoyaltyCard> cardsByCustomer = new HashMap<>();
        cardRepo.findAll().forEach(c -> cardsByCustomer.put(c.getCustomerId(), c));

        SegmentEvaluator ev = evaluator();
        List<LoyaltyCustomer> matches = new ArrayList<>();
        for (LoyaltyCustomer c : all) {
            if (c.getDeletedAt() != null || !Boolean.TRUE.equals(c.getActive())) continue;
            if (ev.matches(criteriaJson, c, cardsByCustomer.get(c.getId()))) matches.add(c);
        }
        return matches;
    }
}
