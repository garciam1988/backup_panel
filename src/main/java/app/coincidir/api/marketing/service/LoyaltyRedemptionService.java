package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyRedemption;
import app.coincidir.api.marketing.domain.LoyaltyReward;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.LoyaltyRedemptionRepository;
import app.coincidir.api.marketing.repository.LoyaltyRewardRepository;
import app.coincidir.api.marketing.util.CustomerHashGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * LoyaltyRedemptionService — Flujo de canje de un premio.
 *
 * Etapas:
 *   1. requestRedemption(customer, reward)
 *      Valida elegibilidad → crea LoyaltyRedemption (PENDING) +
 *      LoyaltyTransaction (redeem_reward con deltas negativos) →
 *      decrementa stock → devuelve redemptionCode al cliente.
 *
 *   2. validateAndRedeem(code, branchId, performedBy)
 *      Mozo en local valida el código → marca como REDEEMED.
 *
 *   3. expirePending() (job)
 *      Marca como EXPIRED los PENDING cuya expires_at pasó. Para cada uno
 *      crea una transaction de adjustment que REVIERTE los deltas.
 *
 *   4. cancel(redemptionId, reason)
 *      Cancela un PENDING (por admin o por el propio cliente).
 *      Revierte los deltas como en el flujo de expiración.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyRedemptionService {

    private final LoyaltyRedemptionRepository redemptionRepo;
    private final LoyaltyRewardRepository rewardRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyRewardService rewardService;
    private final LoyaltyTransactionService transactionService;

    /** TTL del canje en horas (configurable a futuro). */
    private static final int REDEMPTION_TTL_HOURS = 24;

    @Transactional
    public RequestResult request(Long customerId, Long rewardId, String branchId) {
        LoyaltyCustomer customer = customerRepo.findById(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
        LoyaltyReward reward = rewardRepo.findByIdAndDeletedAtIsNull(rewardId)
            .orElseThrow(() -> new IllegalArgumentException("Premio no encontrado"));

        LoyaltyRewardService.EligibilityResult elig =
            rewardService.checkEligibility(customer, reward, branchId);
        if (!elig.eligible()) {
            return RequestResult.rejected(elig.reason());
        }

        // Crear la redemption en PENDING
        LoyaltyRedemption red = new LoyaltyRedemption();
        red.setCustomerId(customer.getId());
        red.setRewardId(reward.getId());
        red.setRedemptionCode(generateUniqueCode());
        red.setStampsCost(reward.getCostStamps() == null ? 0 : reward.getCostStamps());
        red.setPointsCost(reward.getCostPoints() == null ? 0 : reward.getCostPoints());
        red.setCashbackCost(reward.getCostCashback() == null ? BigDecimal.ZERO : reward.getCostCashback());
        red.setStatus(LoyaltyRedemption.Status.PENDING);
        red.setExpiresAt(Instant.now().plus(REDEMPTION_TTL_HOURS, ChronoUnit.HOURS));
        red = redemptionRepo.save(red);

        // Registrar la transaction (deltas negativos)
        transactionService.record(new LoyaltyTransactionService.RecordInput(
            customer.getId(),
            "redeem_reward",
            negative(red.getStampsCost()),
            negative(red.getPointsCost()),
            red.getCashbackCost().negate(),
            null,
            branchId,
            null, null,
            reward.getId(),
            red.getId(),
            null,
            "redemption",
            null,
            "Canje pendiente: " + reward.getName()
        ));

        rewardService.decrementStock(reward.getId());
        log.info("Redemption creada code={} customer={} reward={}",
            red.getRedemptionCode(), customer.getId(), reward.getId());

        return RequestResult.accepted(red);
    }

    private Integer negative(Integer v) { return v == null ? 0 : -Math.abs(v); }

    private String generateUniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = CustomerHashGenerator.newRedemptionCode();
            if (redemptionRepo.findByRedemptionCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("No se pudo generar redemption code único");
    }

    /**
     * Mozo en el local valida el código del cliente. Solo procesa PENDING
     * dentro de vigencia.
     */
    @Transactional
    public ValidateResult validateAndRedeem(String code, String branchId, String performedBy) {
        Optional<LoyaltyRedemption> opt = redemptionRepo.findByRedemptionCode(code);
        if (opt.isEmpty()) return ValidateResult.rejected("Código inválido");
        LoyaltyRedemption red = opt.get();

        if (red.getStatus() != LoyaltyRedemption.Status.PENDING) {
            return ValidateResult.rejected("Este canje ya fue " + red.getStatus().name().toLowerCase());
        }
        if (red.getExpiresAt() != null && red.getExpiresAt().isBefore(Instant.now())) {
            // Side effect: expirar ahora
            expireOne(red);
            return ValidateResult.rejected("El canje venció");
        }

        red.setStatus(LoyaltyRedemption.Status.REDEEMED);
        red.setRedeemedAt(Instant.now());
        red.setRedeemedBranch(branchId);
        red.setRedeemedByUser(performedBy);
        redemptionRepo.save(red);

        log.info("Redemption canjeada code={} branch={} user={}", code, branchId, performedBy);
        return ValidateResult.accepted(red);
    }

    /** Cancela un PENDING (por admin o cliente). Revierte deltas. */
    @Transactional
    public void cancel(Long redemptionId, String reason) {
        LoyaltyRedemption red = redemptionRepo.findById(redemptionId).orElseThrow();
        if (red.getStatus() != LoyaltyRedemption.Status.PENDING) return;

        red.setStatus(LoyaltyRedemption.Status.CANCELLED);
        red.setCancelledAt(Instant.now());
        red.setCancellationReason(reason);
        redemptionRepo.save(red);

        revertDeltas(red, "Cancelado: " + reason);
    }

    private void expireOne(LoyaltyRedemption red) {
        red.setStatus(LoyaltyRedemption.Status.EXPIRED);
        red.setCancelledAt(Instant.now());
        red.setCancellationReason("Vencido por inactividad");
        redemptionRepo.save(red);
        revertDeltas(red, "Canje vencido sin uso");
    }

    private void revertDeltas(LoyaltyRedemption red, String reason) {
        transactionService.record(new LoyaltyTransactionService.RecordInput(
            red.getCustomerId(),
            "adjustment",
            red.getStampsCost(),
            red.getPointsCost(),
            red.getCashbackCost(),
            null, null, null, null,
            red.getRewardId(),
            red.getId(),
            null,
            "system",
            null,
            reason
        ));
        rewardService.incrementStock(red.getRewardId());
    }

    /** Job de expiración (lo llama el scheduler del Bloque 4). */
    @Transactional
    public int expirePending() {
        List<LoyaltyRedemption> due = redemptionRepo.findExpiredPending(Instant.now());
        int n = 0;
        for (LoyaltyRedemption r : due) { expireOne(r); n++; }
        if (n > 0) log.info("Expiradas {} redemptions PENDING vencidas", n);
        return n;
    }

    public List<LoyaltyRedemption> listForCustomer(Long customerId) {
        return redemptionRepo.findByCustomerIdOrderByRequestedAtDesc(customerId);
    }

    public List<LoyaltyRedemption> pendingForCustomer(Long customerId) {
        return redemptionRepo.findByCustomerIdAndStatusOrderByRequestedAtDesc(
            customerId, LoyaltyRedemption.Status.PENDING);
    }

    public record RequestResult(boolean accepted, LoyaltyRedemption redemption, String reasonIfRejected) {
        public static RequestResult accepted(LoyaltyRedemption r) { return new RequestResult(true, r, null); }
        public static RequestResult rejected(String reason)       { return new RequestResult(false, null, reason); }
    }

    public record ValidateResult(boolean accepted, LoyaltyRedemption redemption, String reasonIfRejected) {
        public static ValidateResult accepted(LoyaltyRedemption r) { return new ValidateResult(true, r, null); }
        public static ValidateResult rejected(String reason)       { return new ValidateResult(false, null, reason); }
    }
}
