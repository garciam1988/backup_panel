package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.repository.CampaignRecipientRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.LoyaltyRedemptionRepository;
import app.coincidir.api.marketing.repository.LoyaltyTransactionRepository;
import app.coincidir.api.marketing.repository.MarketingCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * MarketingReportsController — KPIs para el dashboard de /marketing.
 *
 * Endpoints:
 *   GET /overview?days=30   Resumen general
 *   GET /activity?days=30   Stats de actividad transaccional
 *
 * Las queries más complejas (cohorts, retention) van por la view
 * v_loyalty_daily_stats consultada directamente desde el frontend
 * en Bloque 5.
 */
@RestController
@RequestMapping("/api/admin/marketing/reports")
@RequiredArgsConstructor
public class MarketingReportsController {

    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyTransactionRepository txRepo;
    private final LoyaltyRedemptionRepository redemptionRepo;
    private final MarketingCampaignRepository campaignRepo;
    private final CampaignRecipientRepository recipientRepo;

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(value = "days", defaultValue = "30") int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);

        Map<String, Object> result = new HashMap<>();
        result.put("rangeDays", days);
        result.put("totalCustomers", customerRepo.countByDeletedAtIsNullAndActiveTrue());
        result.put("totalTransactions", txRepo.countByCreatedAtBetween(from, now));
        result.put("totalStampEarns", txRepo.countByTransactionTypeAndCreatedAtBetween("earn_stamps", from, now));
        result.put("totalRedemptions", txRepo.countByTransactionTypeAndCreatedAtBetween("redeem_reward", from, now));
        return result;
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(@RequestParam(value = "days", defaultValue = "7") int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);
        Map<String, Object> result = new HashMap<>();
        result.put("rangeDays", days);
        result.put("transactions", txRepo.countByCreatedAtBetween(from, now));
        result.put("earnStamps", txRepo.countByTransactionTypeAndCreatedAtBetween("earn_stamps", from, now));
        result.put("earnPoints", txRepo.countByTransactionTypeAndCreatedAtBetween("earn_points", from, now));
        result.put("earnCashback", txRepo.countByTransactionTypeAndCreatedAtBetween("earn_cashback", from, now));
        result.put("redemptions", txRepo.countByTransactionTypeAndCreatedAtBetween("redeem_reward", from, now));
        return result;
    }
}
