package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.dto.MarketingDtos.CardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponApplyRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponApplyResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.EarnRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.EarnResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.ValidateRedemptionRequest;
import app.coincidir.api.marketing.service.CouponService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * StaffLoyaltyController — Endpoints que usa el panel del MOZO en local.
 *
 * Operaciones:
 *   POST /api/admin/marketing/staff/earn          Sumar stamps/puntos/cashback
 *   POST /api/admin/marketing/staff/validate      Validar redemption code
 *   POST /api/admin/marketing/staff/apply-coupon  Aplicar cupón
 *
 * Requiere JWT panel (mismo que cualquier admin endpoint). En Bloque 9 se
 * puede agregar un sub-rol "staff_local" con permisos limitados.
 */
@RestController
@RequestMapping("/api/admin/marketing/staff")
@RequiredArgsConstructor
public class StaffLoyaltyController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyTransactionService transactionService;
    private final LoyaltyRedemptionService redemptionService;
    private final CouponService couponService;

    @PostMapping("/earn")
    public ResponseEntity<?> earn(@RequestBody EarnRequest req, Authentication auth) {
        try {
            LoyaltyCustomer customer = resolveCustomer(req);
            if (customer == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
            }
            String performedBy = auth != null ? auth.getName() : "staff";

            String txType = inferEarnType(req);
            var input = new LoyaltyTransactionService.RecordInput(
                customer.getId(),
                txType,
                req.stamps() != null ? req.stamps() : (req.purchaseAmount() != null || (req.points() == null && req.cashback() == null) ? 1 : 0),
                req.points(),
                req.cashback(),
                req.purchaseAmount(),
                req.branchId(),
                req.reservationTableSlug(),
                req.reservationRecordId(),
                null, null, null,
                req.source() != null ? req.source() : "staff_scan",
                performedBy,
                req.notes()
            );

            var result = transactionService.record(input);
            String message = buildMessage(result);

            return ResponseEntity.ok(new EarnResponse(
                result.transaction().getId(),
                customer.getCustomerHash(),
                result.card().getCurrentStamps(),
                result.card().getCurrentPoints(),
                result.card().getCashbackBalance(),
                result.stampsToReward(),
                message
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String inferEarnType(EarnRequest req) {
        if (req.points() != null && req.points() != 0) return "earn_points";
        if (req.cashback() != null && req.cashback().compareTo(BigDecimal.ZERO) != 0) return "earn_cashback";
        return "earn_stamps";
    }

    private String buildMessage(LoyaltyTransactionService.RecordResult r) {
        var t = r.transaction();
        StringBuilder sb = new StringBuilder();
        if (t.getStampsDelta() > 0)
            sb.append("¡Sumaste ").append(t.getStampsDelta()).append(" estampilla").append(t.getStampsDelta() == 1 ? "" : "s").append("!");
        if (t.getPointsDelta() > 0)
            sb.append(sb.length() > 0 ? " " : "").append("Sumaste ").append(t.getPointsDelta()).append(" puntos.");
        if (t.getCashbackDelta().compareTo(BigDecimal.ZERO) > 0)
            sb.append(sb.length() > 0 ? " " : "").append("Sumaste $").append(t.getCashbackDelta()).append(" de cashback.");
        if (r.stampsToReward() > 0)
            sb.append(" Te faltan ").append(r.stampsToReward()).append(" para tu premio.");
        else if (r.stampsToReward() == 0)
            sb.append(" ¡Llegaste al premio! Podés canjearlo cuando quieras.");
        return sb.toString();
    }

    private LoyaltyCustomer resolveCustomer(EarnRequest req) {
        if (req.customerHash() != null && !req.customerHash().isBlank()) {
            return customerService.findByHash(req.customerHash()).orElse(null);
        }
        if (req.phone() != null && !req.phone().isBlank()) {
            return customerService.findByPhone(req.phone()).orElse(null);
        }
        return null;
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody ValidateRedemptionRequest req, Authentication auth) {
        if (req.redemptionCode() == null || req.redemptionCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "redemptionCode es requerido"));
        }
        String performedBy = auth != null ? auth.getName() : "staff";
        var result = redemptionService.validateAndRedeem(
            req.redemptionCode().trim().toUpperCase(),
            req.branchId(),
            performedBy
        );
        if (!result.accepted()) {
            return ResponseEntity.status(409).body(Map.of(
                "accepted", false,
                "error", result.reasonIfRejected()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "accepted", true,
            "redemption", result.redemption()
        ));
    }

    @PostMapping("/apply-coupon")
    public ResponseEntity<?> applyCoupon(@RequestBody CouponApplyRequest req, Authentication auth) {
        if (req.code() == null) return ResponseEntity.badRequest().body(Map.of("error", "code requerido"));
        if (req.customerId() == null) return ResponseEntity.badRequest().body(Map.of("error", "customerId requerido"));
        String performedBy = auth != null ? auth.getName() : "staff";
        var result = couponService.apply(req.code(), req.customerId(),
            req.purchaseAmount() == null ? BigDecimal.ZERO : req.purchaseAmount(),
            req.branchId(), performedBy);
        return ResponseEntity.ok(new CouponApplyResponse(
            result.accepted(), result.discountApplied(), result.reasonIfRejected()
        ));
    }

    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam(value = "hash", required = false) String hash,
                                    @RequestParam(value = "phone", required = false) String phone) {
        LoyaltyCustomer c = null;
        if (hash != null && !hash.isBlank()) c = customerService.findByHash(hash).orElse(null);
        else if (phone != null && !phone.isBlank()) c = customerService.findByPhone(phone).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(Map.of(
            "customerId", c.getId(),
            "customerHash", c.getCustomerHash(),
            "firstName", c.getFirstName(),
            "lastName", c.getLastName() == null ? "" : c.getLastName(),
            "phone", c.getPhone()
        ));
    }
}
