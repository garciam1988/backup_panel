package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.domain.LoyaltyTransaction;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.LoyaltyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LoyaltyTransactionService — Corazón del módulo: registra movimientos
 * sobre la tarjeta y mantiene los balances actualizados.
 *
 * Invariante crítico: TODA modificación de balances pasa por acá. El método
 * record() crea siempre la LoyaltyTransaction (audit log inmutable) y
 * sincronizadamente actualiza LoyaltyCard. Esto garantiza:
 *   1. Cada cambio queda auditado con quién, cuándo, dónde y por qué.
 *   2. Los balances pueden reconstruirse 100% desde transactions si hace falta.
 *
 * Casos especiales:
 *   - Si stampsRequired se alcanza y stampsResetOnRedeem=false, no auto-canjea
 *     (el cliente elige cuándo canjear). Solo se reportan en el response los
 *     stamps necesarios para el premio principal.
 *   - Si se intenta sumar puntos pero el programa los tiene deshabilitados,
 *     se ignora silenciosamente (no se crea tx con points_delta).
 *   - Cashback se calcula automáticamente si purchaseAmount viene y el flag
 *     está habilitado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyTransactionService {

    private final LoyaltyTransactionRepository txRepo;
    private final LoyaltyCardRepository cardRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyProgramService programService;
    private final LoyaltyCustomerService customerService;
    private final EarnRuleEvaluator earnRuleEvaluator;

    /**
     * Registra un movimiento sobre la tarjeta del cliente.
     *
     * Nota sobre concurrencia: si dos requests intentan modificar la misma
     * tarjeta simultáneamente, MySQL serializa los UPDATE vía row locks de
     * InnoDB; con READ_COMMITTED no debería haber lost-updates en el flujo
     * típico. Si en algún momento se vuelve un problema, sumar @Version a
     * LoyaltyCard y reintentar OptimisticLockingFailureException (ya está
     * declarada en imports, listo para activar @Retryable cuando se agregue
     * spring-retry al pom).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RecordResult record(RecordInput in) {
        LoyaltyCustomer customer = customerRepo.findById(in.customerId())
            .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado: " + in.customerId()));

        LoyaltyCard card = customerService.ensureCard(customer);
        LoyaltyProgram program = programService.getActiveProgram();

        // Calcular deltas reales según flags del programa
        int stampsDelta = program.getStampsEnabled() ? defaultInt(in.stampsDelta(), 0) : 0;
        int pointsDelta = program.getPointsEnabled() ? defaultInt(in.pointsDelta(), 0) : 0;
        BigDecimal cashbackDelta = program.getCashbackEnabled()
            ? Optional.ofNullable(in.cashbackDelta()).orElse(BigDecimal.ZERO)
            : BigDecimal.ZERO;

        // Auto-calcular puntos desde monto de compra si aplica
        if (pointsDelta == 0 && in.purchaseAmount() != null && program.getPointsEnabled()
            && program.getPointsPerCurrency() != null
            && in.transactionType() != null && in.transactionType().startsWith("earn_")) {
            BigDecimal pts = in.purchaseAmount().multiply(program.getPointsPerCurrency());
            pointsDelta = pts.setScale(0, RoundingMode.DOWN).intValue();
        }

        // Auto-calcular cashback si aplica
        if (cashbackDelta.compareTo(BigDecimal.ZERO) == 0 && in.purchaseAmount() != null
            && program.getCashbackEnabled() && program.getCashbackPercentage() != null
            && in.transactionType() != null && in.transactionType().startsWith("earn_")) {
            BigDecimal minP = program.getCashbackMinPurchase();
            if (minP == null || in.purchaseAmount().compareTo(minP) >= 0) {
                BigDecimal pct = program.getCashbackPercentage().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                cashbackDelta = in.purchaseAmount().multiply(pct).setScale(2, RoundingMode.HALF_UP);
                if (program.getCashbackMaxPerPurchase() != null
                    && cashbackDelta.compareTo(program.getCashbackMaxPerPurchase()) > 0) {
                    cashbackDelta = program.getCashbackMaxPerPurchase();
                }
            }
        }

        if (stampsDelta == 0 && pointsDelta == 0 && cashbackDelta.compareTo(BigDecimal.ZERO) == 0
            && !"adjustment".equals(in.transactionType()) && !"refund".equals(in.transactionType())) {
            throw new IllegalArgumentException("La transacción no tiene deltas para aplicar");
        }

        // ── Aplicar reglas dinámicas (Bloque 8) ────────────────────────────
        // Solo evaluamos reglas para earns (no para refund/adjust/redeem que
        // tienen lógica propia). Determinamos el trigger por el tipo de tx:
        //   earn_* via purchase → trigger="purchase"
        //   earn_* manual (sin purchaseAmount) → trigger="manual"
        // Los trigger="enrollment" y "birthday" los dispara un job aparte, no
        // pasan por este método.
        String appliedRulesJson = in.appliedRulesJson();
        if (in.transactionType() != null && in.transactionType().startsWith("earn_")) {
            String ruleTrigger = in.purchaseAmount() != null ? "purchase" : "manual";
            var ctx = new EarnRuleEvaluator.EarnContext(
                customer.getId(),
                in.purchaseAmount(),
                in.branchId(),
                ruleTrigger,
                Instant.now()
            );
            var eval = earnRuleEvaluator.evaluate(ctx, stampsDelta, pointsDelta, cashbackDelta);
            if (!eval.isEmpty()) {
                stampsDelta   = stampsDelta + eval.stamps();
                pointsDelta   = pointsDelta + eval.points();
                cashbackDelta = cashbackDelta.add(eval.cashback());
                // Si el caller ya pasó appliedRulesJson, lo respetamos. Si no,
                // generamos uno con las reglas que matchearon acá.
                if (appliedRulesJson == null) {
                    appliedRulesJson = earnRuleEvaluator.serializeApplied(eval.appliedRules());
                }
                log.info("Reglas dinámicas aplicadas a customer={}: Δstamps={} Δpoints={} Δcashback={} reglas={}",
                    customer.getId(), eval.stamps(), eval.points(), eval.cashback(),
                    eval.appliedRules().size());
            }
        }

        // Crear la transaction (audit log)
        LoyaltyTransaction tx = new LoyaltyTransaction();
        tx.setCustomerId(customer.getId());
        tx.setCardId(card.getId());
        tx.setTransactionType(in.transactionType());
        tx.setStampsDelta(stampsDelta);
        tx.setPointsDelta(pointsDelta);
        tx.setCashbackDelta(cashbackDelta);
        tx.setBranchId(in.branchId());
        tx.setPurchaseAmount(in.purchaseAmount());
        tx.setReservationTableSlug(in.reservationTableSlug());
        tx.setReservationRecordId(in.reservationRecordId());
        tx.setRewardId(in.rewardId());
        tx.setRedemptionId(in.redemptionId());
        tx.setAppliedRulesJson(appliedRulesJson);
        tx.setSource(in.source() != null ? in.source() : "system");
        tx.setPerformedBy(in.performedBy());
        tx.setNotes(in.notes());
        tx = txRepo.save(tx);

        // Actualizar balances de la card
        card.setCurrentStamps(Math.max(0, card.getCurrentStamps() + stampsDelta));
        card.setCurrentPoints(Math.max(0, card.getCurrentPoints() + pointsDelta));
        card.setCashbackBalance(card.getCashbackBalance().add(cashbackDelta).max(BigDecimal.ZERO));

        // Lifetime solo acumula positivos
        if (stampsDelta > 0)   card.setLifetimeStamps(card.getLifetimeStamps() + stampsDelta);
        if (pointsDelta > 0)   card.setLifetimePoints(card.getLifetimePoints() + pointsDelta);
        if (cashbackDelta.compareTo(BigDecimal.ZERO) > 0)
            card.setLifetimeCashback(card.getLifetimeCashback().add(cashbackDelta));

        if (stampsDelta > 0)              card.setLastStampAt(Instant.now());
        if ("redeem_reward".equals(in.transactionType())) card.setLastRedeemAt(Instant.now());

        cardRepo.save(card);

        // Tocar actividad del cliente
        if (!"system".equals(in.source())) {
            customer.setLastActivityAt(Instant.now());
            if (stampsDelta > 0 || pointsDelta > 0 || cashbackDelta.compareTo(BigDecimal.ZERO) > 0) {
                customer.setTotalVisits(customer.getTotalVisits() + 1);
            }
            customerRepo.save(customer);
        }

        int stampsToReward = computeStampsToReward(program, card);
        log.info("Tx registrada id={} customer={} type={} stamps={} points={} cashback={}",
            tx.getId(), customer.getId(), tx.getTransactionType(),
            stampsDelta, pointsDelta, cashbackDelta);

        return new RecordResult(tx, card, stampsToReward);
    }

    private int computeStampsToReward(LoyaltyProgram program, LoyaltyCard card) {
        if (!program.getStampsEnabled() || program.getStampsRequired() == null) return -1;
        int needed = program.getStampsRequired() - card.getCurrentStamps();
        return Math.max(0, needed);
    }

    private int defaultInt(Integer i, int def) { return i == null ? def : i; }

    /**
     * Reconstruye los balances de un cliente desde sus transactions. Útil si
     * se detecta inconsistencia. NO modifica las transactions, solo recalcula
     * la card.
     */
    @Transactional
    public LoyaltyCard recomputeBalances(Long customerId) {
        LoyaltyCard card = cardRepo.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalStateException("Cliente sin tarjeta: " + customerId));

        List<LoyaltyTransaction> all = txRepo.findAllByCustomerOrdered(customerId);
        int curStamps = 0, curPoints = 0, lifeStamps = 0, lifePoints = 0;
        BigDecimal curCashback = BigDecimal.ZERO, lifeCashback = BigDecimal.ZERO;
        Instant lastStamp = null, lastRedeem = null;

        for (LoyaltyTransaction t : all) {
            curStamps = Math.max(0, curStamps + t.getStampsDelta());
            curPoints = Math.max(0, curPoints + t.getPointsDelta());
            curCashback = curCashback.add(t.getCashbackDelta()).max(BigDecimal.ZERO);
            if (t.getStampsDelta() > 0)    lifeStamps += t.getStampsDelta();
            if (t.getPointsDelta() > 0)    lifePoints += t.getPointsDelta();
            if (t.getCashbackDelta().compareTo(BigDecimal.ZERO) > 0)
                lifeCashback = lifeCashback.add(t.getCashbackDelta());
            if (t.getStampsDelta() > 0)    lastStamp = t.getCreatedAt();
            if ("redeem_reward".equals(t.getTransactionType())) lastRedeem = t.getCreatedAt();
        }

        card.setCurrentStamps(curStamps);
        card.setCurrentPoints(curPoints);
        card.setCashbackBalance(curCashback);
        card.setLifetimeStamps(lifeStamps);
        card.setLifetimePoints(lifePoints);
        card.setLifetimeCashback(lifeCashback);
        card.setLastStampAt(lastStamp);
        card.setLastRedeemAt(lastRedeem);
        return cardRepo.save(card);
    }

    public Page<LoyaltyTransaction> history(Long customerId, Pageable pageable) {
        return txRepo.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }

    public List<LoyaltyTransaction> recent(Long customerId) {
        return txRepo.findTop20ByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    // ── DTOs internos ─────────────────────────────────────────────────────

    public record RecordInput(
        Long customerId,
        String transactionType,
        Integer stampsDelta,
        Integer pointsDelta,
        BigDecimal cashbackDelta,
        BigDecimal purchaseAmount,
        String branchId,
        String reservationTableSlug,
        Long reservationRecordId,
        Long rewardId,
        Long redemptionId,
        String appliedRulesJson,
        String source,
        String performedBy,
        String notes
    ) {}

    public record RecordResult(
        LoyaltyTransaction transaction,
        LoyaltyCard card,
        int stampsToReward
    ) {}
}
