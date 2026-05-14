package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyEarnRule;
import app.coincidir.api.marketing.repository.LoyaltyEarnRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * LoyaltyEarnRuleService — CRUD de reglas de earn.
 *
 * Las reglas se almacenan asociadas a un programId (singleton id=1 en esta
 * versión, futuro multi-tenant). El service garantiza defaults defensivos
 * y validación básica del JSON de condiciones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyEarnRuleService {

    private final LoyaltyEarnRuleRepository ruleRepo;
    private final LoyaltyProgramService programService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Page<LoyaltyEarnRule> list(int page, int size) {
        Long programId = programService.getActiveProgram().getId();
        Pageable pageable = PageRequest.of(page, size);
        return ruleRepo.findAllByProgramIdOrderByPriorityDescIdAsc(programId, pageable);
    }

    public List<LoyaltyEarnRule> listAll() {
        Long programId = programService.getActiveProgram().getId();
        return ruleRepo.findAllByProgramIdOrderByPriorityDescIdAsc(programId);
    }

    public Optional<LoyaltyEarnRule> findById(Long id) {
        return ruleRepo.findById(id);
    }

    @Transactional
    public LoyaltyEarnRule create(LoyaltyEarnRule rule) {
        if (rule.getProgramId() == null) {
            rule.setProgramId(programService.getActiveProgram().getId());
        }
        validate(rule);
        applyDefaults(rule);
        return ruleRepo.save(rule);
    }

    @Transactional
    public LoyaltyEarnRule update(Long id, LoyaltyEarnRule update) {
        LoyaltyEarnRule r = ruleRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Regla no encontrada: " + id));
        if (update.getName() != null)            r.setName(update.getName());
        if (update.getDescription() != null)     r.setDescription(update.getDescription());
        if (update.getRuleType() != null)        r.setRuleType(update.getRuleType());
        if (update.getTarget() != null)          r.setTarget(update.getTarget());
        if (update.getMultiplierValue() != null) r.setMultiplierValue(update.getMultiplierValue());
        if (update.getBonusStamps() != null)     r.setBonusStamps(update.getBonusStamps());
        if (update.getBonusPoints() != null)     r.setBonusPoints(update.getBonusPoints());
        if (update.getBonusCashback() != null)   r.setBonusCashback(update.getBonusCashback());
        if (update.getConditionsJson() != null)  r.setConditionsJson(update.getConditionsJson());
        if (update.getPriority() != null)        r.setPriority(update.getPriority());
        if (update.getValidFrom() != null)       r.setValidFrom(update.getValidFrom());
        if (update.getValidUntil() != null)      r.setValidUntil(update.getValidUntil());
        if (update.getActive() != null)          r.setActive(update.getActive());
        validate(r);
        return ruleRepo.save(r);
    }

    @Transactional
    public void delete(Long id) {
        ruleRepo.deleteById(id);
    }

    /** Toggle rápido de active. */
    @Transactional
    public LoyaltyEarnRule toggleActive(Long id) {
        LoyaltyEarnRule r = ruleRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Regla no encontrada: " + id));
        r.setActive(!Boolean.TRUE.equals(r.getActive()));
        return ruleRepo.save(r);
    }

    private void validate(LoyaltyEarnRule r) {
        if (r.getName() == null || r.getName().isBlank())
            throw new IllegalArgumentException("Nombre es requerido");
        if (r.getRuleType() == null)
            throw new IllegalArgumentException("ruleType es requerido (MULTIPLIER | BONUS)");
        if (r.getTarget() == null)
            throw new IllegalArgumentException("target es requerido (STAMPS | POINTS | CASHBACK | ALL)");
        if (r.getRuleType() == LoyaltyEarnRule.RuleType.MULTIPLIER) {
            if (r.getMultiplierValue() == null)
                throw new IllegalArgumentException("multiplierValue es requerido para MULTIPLIER");
        }
        if (r.getRuleType() == LoyaltyEarnRule.RuleType.BONUS) {
            if (r.getBonusStamps() == null && r.getBonusPoints() == null && r.getBonusCashback() == null)
                throw new IllegalArgumentException("Indicá al menos uno: bonusStamps, bonusPoints o bonusCashback");
        }
        // conditionsJson puede ser "{}" (sin filtros), pero debe ser JSON válido
        String json = r.getConditionsJson();
        if (json == null || json.isBlank()) {
            r.setConditionsJson("{}");
        } else {
            try {
                objectMapper.readTree(json);
            } catch (Exception e) {
                throw new IllegalArgumentException("conditionsJson no es JSON válido: " + e.getMessage());
            }
        }
    }

    private void applyDefaults(LoyaltyEarnRule r) {
        if (r.getPriority() == null) r.setPriority(0);
        if (r.getActive() == null) r.setActive(true);
    }
}
