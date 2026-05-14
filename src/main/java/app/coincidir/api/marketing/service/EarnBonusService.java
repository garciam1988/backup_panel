package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyEarnRule;
import app.coincidir.api.marketing.event.CustomerEnrolledEvent;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.LoyaltyEarnRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;

/**
 * EarnBonusService — Dispara bonuses automáticos por eventos no-compra:
 *
 *   - ENROLLMENT: cuando un cliente se enrola por primera vez, evalúa
 *     reglas con trigger="enrollment" y crea una transaction tipo
 *     "earn_bonus" con los deltas resultantes. Se llama desde el flujo
 *     de enrolamiento (LoyaltyCustomerService.enroll).
 *
 *   - BIRTHDAY: lo dispara un job diario (BirthdayBonusJob), que busca
 *     clientes cuyo birth_date corresponda al día actual.
 *
 * Para que las reglas de cumpleaños/enrolamiento se ejecuten SIN una compra,
 * llamamos directo a EarnRuleEvaluator.evaluate(...) con base=0 y trigger
 * apropiado. Las reglas son las únicas que aportan deltas, así que el
 * resultado del evaluate ES el delta que vamos a aplicar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarnBonusService {

    private final EarnRuleEvaluator earnRuleEvaluator;
    private final LoyaltyTransactionService transactionService;
    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyEarnRuleRepository ruleRepo;
    private final LoyaltyProgramService programService;

    /**
     * Listener del evento de enrolamiento. Se ejecuta DESPUÉS del commit
     * de la transacción de enrolamiento (AFTER_COMMIT) para garantizar que
     * el customer ya está persistido y todos los flujos posteriores ven
     * un estado consistente.
     *
     * IMPORTANTE: como AFTER_COMMIT corre fuera del contexto transaccional
     * original, necesitamos REQUIRES_NEW para que el método tenga su propia
     * transacción nueva. Si usáramos @Transactional default (REQUIRED),
     * Spring rechaza el bean en startup porque sería ambiguo.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCustomerEnrolled(CustomerEnrolledEvent event) {
        try {
            applyTriggerBonus(event.customer(), "enrollment");
        } catch (Exception e) {
            log.warn("Error aplicando bonus de enrolamiento a customer={}: {}",
                event.customer().getId(), e.getMessage());
        }
    }

    /**
     * Job diario: busca clientes cuyo birth_date coincida con hoy y les
     * dispara reglas con trigger="birthday".
     *
     * @return cantidad de clientes procesados (con o sin bonus efectivo)
     */
    @Transactional
    public int runBirthdayBonusJob() {
        // ¿Hay reglas birthday activas? Si no, salimos rápido.
        Long programId = programService.getActiveProgram().getId();
        List<LoyaltyEarnRule> rules = ruleRepo.findActiveAt(programId, Instant.now());
        boolean anyBirthday = rules.stream().anyMatch(r -> {
            // Mirá el trigger sin parsear JSON: contiene "birthday"
            String json = r.getConditionsJson();
            return json != null && json.contains("\"birthday\"");
        });
        if (!anyBirthday) {
            log.debug("BirthdayBonusJob: no hay reglas activas con trigger=birthday, skip");
            return 0;
        }

        MonthDay today = MonthDay.from(LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")));
        // Buscamos a mano por birth_date matching MM-DD (independiente del año)
        List<LoyaltyCustomer> celebrants = customerRepo.findAll().stream()
            .filter(c -> c.getBirthDate() != null
                && c.getBirthDate().getMonth().equals(today.getMonth())
                && c.getBirthDate().getDayOfMonth() == today.getDayOfMonth())
            .toList();

        log.info("BirthdayBonusJob: {} clientes de cumpleaños hoy", celebrants.size());
        for (LoyaltyCustomer c : celebrants) {
            try {
                applyTriggerBonus(c, "birthday");
            } catch (Exception e) {
                log.warn("BirthdayBonusJob falló para customer={}: {}", c.getId(), e.getMessage());
            }
        }
        return celebrants.size();
    }

    /**
     * Implementación común para enrolment / birthday. Evalúa reglas con
     * base=0, y si hay deltas crea una transacción tipo earn_bonus.
     */
    private void applyTriggerBonus(LoyaltyCustomer customer, String trigger) {
        var ctx = new EarnRuleEvaluator.EarnContext(
            customer.getId(),
            null,            // sin compra
            null,            // sin sucursal específica
            trigger,
            Instant.now()
        );
        var eval = earnRuleEvaluator.evaluate(ctx, 0, 0, BigDecimal.ZERO);
        if (eval.isEmpty()) {
            log.debug("Trigger {} no generó bonus para customer={}", trigger, customer.getId());
            return;
        }

        log.info("Aplicando bonus {} a customer={}: stamps={} points={} cashback={}",
            trigger, customer.getId(), eval.stamps(), eval.points(), eval.cashback());

        // Creamos una transacción tipo earn_bonus. El TransactionService NO
        // re-evalúa las reglas porque el transactionType no empieza con "earn_"
        // como purchase — usamos un tipo específico "earn_bonus" que pasa por
        // el flujo de earn (acumula lifetime, last_stamp_at, etc) pero queremos
        // evitar doble evaluación. Para eso, pasamos appliedRulesJson armado.
        String appliedJson = earnRuleEvaluator.serializeApplied(eval.appliedRules());

        // OJO: el record() actual SÍ re-evalúa para "earn_*". Hay que evitarlo
        // pasando los deltas finales y la cuenta ya hecha. Resolvemos llamando
        // con purchaseAmount=null y stamps/points/cashback explícitos. El
        // evaluator del servicio va a llamarse otra vez, pero con trigger
        // "manual" — y nuestras reglas requieren trigger="enrollment"/"birthday"
        // así que NO van a matchear. Seguro.
        var input = new LoyaltyTransactionService.RecordInput(
            customer.getId(),
            "earn_bonus",
            eval.stamps(),
            eval.points(),
            eval.cashback(),
            null,            // purchaseAmount
            null,            // branchId
            null,            // reservationTableSlug
            null,            // reservationRecordId
            null,            // rewardId
            null,            // redemptionId
            appliedJson,     // applied_rules_json
            "system",        // source
            "earn-bonus-job",// performedBy
            "Bonus automático: " + trigger
        );
        transactionService.record(input);
    }
}
