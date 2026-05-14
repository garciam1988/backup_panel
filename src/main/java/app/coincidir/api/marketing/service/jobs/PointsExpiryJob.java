package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.domain.LoyaltyTransaction;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyTransactionRepository;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * PointsExpiryJob — Vence puntos y cashback no usados según la política
 * del programa (loyalty_program.points_expiry_days y .cashback_expiry_days).
 *
 * Algoritmo simple FIFO por edad de la ganancia:
 *   1. Para cada cliente con balance positivo en puntos/cashback:
 *   2. Sumar transactions de earn_points / earn_cashback más antiguas que
 *      el threshold (points_expiry_days días atrás), descontando las que
 *      ya fueron consumidas posteriormente por redeem_reward o expire.
 *   3. Si queda balance "viejo" sin consumir, crear una transaction tipo
 *      'expire' con delta negativo igual a ese balance vencido.
 *
 * Notas:
 *   - Si points_expiry_days = NULL, los puntos NO expiran y se saltea.
 *   - Si cashback_expiry_days = NULL, idem cashback.
 *   - Las estampillas no expiran nunca por diseño (son progreso hacia
 *     un premio específico, no moneda con tiempo).
 *   - Se ejecuta una vez por día a las 03:30 AM (low-traffic).
 *
 * Implementación: en MVP usamos un enfoque conservador — si la última
 * transacción positiva del cliente es más vieja que el threshold y el
 * balance > 0, expiramos el balance entero. Es una aproximación que se
 * puede refinar en el futuro con FIFO real por lote de earn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsExpiryJob {

    private final LoyaltyProgramService programService;
    private final LoyaltyCardRepository cardRepo;
    private final LoyaltyTransactionRepository txRepo;
    private final LoyaltyTransactionService transactionService;
    private final BotConfigRepository botConfigRepo;

    /** Cron: diario a las 03:30 AM. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void run() {
        if (!isMarketingEnabled()) return;
        LoyaltyProgram program = programService.getActiveProgram();

        Integer pointsExpiryDays = program.getPointsExpiryDays();
        Integer cashbackExpiryDays = program.getCashbackExpiryDays();

        if (pointsExpiryDays == null && cashbackExpiryDays == null) {
            log.debug("[PointsExpiryJob] tick — programa sin política de expiración, skip");
            return;
        }

        int expiredCount = 0;
        List<LoyaltyCard> cards = cardRepo.findAll();
        for (LoyaltyCard card : cards) {
            try {
                if (expireCardBalances(card, program, pointsExpiryDays, cashbackExpiryDays)) {
                    expiredCount++;
                }
            } catch (Exception e) {
                log.warn("[PointsExpiryJob] error en card customer={}: {}",
                    card.getCustomerId(), e.getMessage());
            }
        }
        log.info("[PointsExpiryJob] vencimientos aplicados a {} clientes", expiredCount);
    }

    /**
     * Aplica vencimiento sobre una card individual. Devuelve true si se
     * generaron transactions de expiración.
     */
    private boolean expireCardBalances(LoyaltyCard card, LoyaltyProgram program,
                                       Integer pointsExpiryDays, Integer cashbackExpiryDays) {
        boolean did = false;

        // ── Puntos ──
        if (pointsExpiryDays != null && pointsExpiryDays > 0
            && card.getCurrentPoints() > 0
            && Boolean.TRUE.equals(program.getPointsEnabled())) {

            Instant cutoff = Instant.now().minus(pointsExpiryDays, ChronoUnit.DAYS);
            if (lastEarnIsOlderThan(card.getCustomerId(), "earn_points", cutoff)) {
                int toExpire = card.getCurrentPoints();
                transactionService.record(new LoyaltyTransactionService.RecordInput(
                    card.getCustomerId(), "expire",
                    0, -toExpire, BigDecimal.ZERO,
                    null, null, null, null, null, null, null,
                    "system", "system",
                    "Vencimiento automático: " + toExpire + " puntos sin uso por más de " + pointsExpiryDays + " días"
                ));
                did = true;
                log.info("[PointsExpiryJob] customer={} expiró {} puntos",
                    card.getCustomerId(), toExpire);
            }
        }

        // ── Cashback ──
        if (cashbackExpiryDays != null && cashbackExpiryDays > 0
            && card.getCashbackBalance().signum() > 0
            && Boolean.TRUE.equals(program.getCashbackEnabled())) {

            Instant cutoff = Instant.now().minus(cashbackExpiryDays, ChronoUnit.DAYS);
            if (lastEarnIsOlderThan(card.getCustomerId(), "earn_cashback", cutoff)) {
                BigDecimal toExpire = card.getCashbackBalance();
                transactionService.record(new LoyaltyTransactionService.RecordInput(
                    card.getCustomerId(), "expire",
                    0, 0, toExpire.negate(),
                    null, null, null, null, null, null, null,
                    "system", "system",
                    "Vencimiento automático: $" + toExpire + " de cashback sin uso por más de " + cashbackExpiryDays + " días"
                ));
                did = true;
                log.info("[PointsExpiryJob] customer={} expiró ${} cashback",
                    card.getCustomerId(), toExpire);
            }
        }

        return did;
    }

    /**
     * Devuelve true si la última transaction "earn_*" del cliente es más
     * antigua que el cutoff. Aproximación conservadora: si el cliente
     * sumó hace 100 días y nada después, todo lo que tenga ahora es viejo.
     */
    private boolean lastEarnIsOlderThan(Long customerId, String earnType, Instant cutoff) {
        // Usamos las últimas 20 transactions, suficiente para detectar la última earn
        List<LoyaltyTransaction> recent = txRepo.findTop20ByCustomerIdOrderByCreatedAtDesc(customerId);
        Instant lastEarn = null;
        for (LoyaltyTransaction t : recent) {
            if (earnType.equals(t.getTransactionType())) {
                lastEarn = t.getCreatedAt();
                break;
            }
        }
        return lastEarn != null && lastEarn.isBefore(cutoff);
    }

    private boolean isMarketingEnabled() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        return cfg.isPresent() && Boolean.TRUE.equals(cfg.get().getMarketingEnabled());
    }
}
