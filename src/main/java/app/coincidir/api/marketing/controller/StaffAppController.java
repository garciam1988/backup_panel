package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.StaffUser;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponApplyRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponApplyResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.EarnRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.EarnResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.ValidateRedemptionRequest;
import app.coincidir.api.marketing.service.CouponService;
import app.coincidir.api.marketing.service.LoyaltyCardService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import app.coincidir.api.marketing.service.StaffUserService;
import app.coincidir.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Staff App Controller — Endpoints que usa la PWA del mozo en /staff.
 *
 * Auth:
 *   - POST /api/staff-app/auth/login con PIN devuelve JWT staff con
 *     subject="staff:{id}" y claims role=STAFF, staffId, staffName.
 *   - Endpoints subsiguientes pasan por el JwtAuthFilter standard.
 *
 * Operaciones (todas con auditoría por staff_user_id en performedBy):
 *   GET  /api/staff-app/lookup?hash={hash}     Datos del cliente + premios
 *   POST /api/staff-app/earn                   Cargar stamps/puntos/cashback
 *   POST /api/staff-app/validate               Validar redemption code
 *   POST /api/staff-app/apply-coupon           Aplicar cupón
 *   GET  /api/staff-app/me                     Datos del staff logueado
 */
@Slf4j
@RestController
@RequestMapping("/api/staff-app")
@RequiredArgsConstructor
public class StaffAppController {

    private final StaffUserService staffService;
    private final JwtService jwtService;
    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyProgramService programService;
    private final LoyaltyTransactionService transactionService;
    private final LoyaltyRedemptionService redemptionService;
    private final LoyaltyRewardService rewardService;
    private final CouponService couponService;

    // ── Auth ─────────────────────────────────────────────────────────────

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String pin = body.get("pin") != null ? body.get("pin").toString() : "";
        Optional<StaffUser> maybe = staffService.authenticate(pin);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "PIN inválido"));
        }
        StaffUser staff = maybe.get();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("role", "STAFF");
        claims.put("staffId", staff.getId());
        claims.put("staffName", staff.getName());
        claims.put("staffRole", staff.getRole());

        String token = jwtService.generate("staff:" + staff.getId(), claims);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "staff", staffToDto(staff)
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        Long staffId = extractStaffId(auth);
        if (staffId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "No autenticado"));
        }
        return staffService.findById(staffId)
            .<ResponseEntity<?>>map(s -> ResponseEntity.ok(staffToDto(s)))
            .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Staff no existe")));
    }

    // ── Operaciones sobre clientes ───────────────────────────────────────

    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam("hash") String hash) {
        Optional<LoyaltyCustomer> maybe = customerService.findByHash(hash);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
        }
        LoyaltyCustomer cust = maybe.get();
        var card = cardService.getOrCreate(cust);
        var recent = transactionService.recent(cust.getId());
        var program = programService.getActiveProgram();
        var rewards = rewardService.listAvailableNow(program.getId());

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", cust.getId());
        customer.put("customerHash", cust.getCustomerHash());
        customer.put("firstName", cust.getFirstName());
        customer.put("lastName", cust.getLastName());
        customer.put("phone", cust.getPhone());
        customer.put("email", cust.getEmail());
        customer.put("totalVisits", cust.getTotalVisits());
        result.put("customer", customer);

        Map<String, Object> cardDto = new LinkedHashMap<>();
        cardDto.put("currentStamps", card.getCurrentStamps());
        cardDto.put("currentPoints", card.getCurrentPoints());
        cardDto.put("cashbackBalance", card.getCashbackBalance());
        cardDto.put("totalStampsEarned", card.getLifetimeStamps());
        result.put("card", cardDto);

        Map<String, Object> programDto = new LinkedHashMap<>();
        programDto.put("id", program.getId());
        programDto.put("name", program.getName());
        programDto.put("stampsEnabled", program.getStampsEnabled());
        programDto.put("pointsEnabled", program.getPointsEnabled());
        programDto.put("cashbackEnabled", program.getCashbackEnabled());
        programDto.put("stampsRequired", program.getStampsRequired());
        programDto.put("stampsRewardText", program.getStampsRewardText());
        result.put("program", programDto);

        List<Map<String, Object>> rewardDtos = rewards.stream().map(r -> {
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("id", r.getId());
            rd.put("name", r.getName());
            rd.put("description", r.getDescription());
            rd.put("costStamps", r.getCostStamps());
            rd.put("costPoints", r.getCostPoints());
            rd.put("costCashback", r.getCostCashback());
            rd.put("canRedeem", canAffordReward(card, r));
            return rd;
        }).toList();
        result.put("rewards", rewardDtos);

        List<Map<String, Object>> txDtos = recent.stream().limit(10).map(t -> {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("id", t.getId());
            td.put("type", t.getTransactionType());
            td.put("stampsDelta", t.getStampsDelta());
            td.put("pointsDelta", t.getPointsDelta());
            td.put("cashbackDelta", t.getCashbackDelta());
            td.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
            td.put("performedBy", t.getPerformedBy());
            return td;
        }).toList();
        result.put("recentTransactions", txDtos);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/earn")
    public ResponseEntity<?> earn(@RequestBody EarnRequest req, Authentication auth) {
        try {
            LoyaltyCustomer customer = resolveCustomer(req);
            if (customer == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
            }
            String performedBy = extractPerformedBy(auth);

            String txType = inferEarnType(req);
            int stampsDelta = req.stamps() != null
                ? req.stamps()
                : (req.purchaseAmount() != null || (req.points() == null && req.cashback() == null) ? 1 : 0);

            var input = new LoyaltyTransactionService.RecordInput(
                customer.getId(),
                txType,
                stampsDelta,
                req.points(),
                req.cashback(),
                req.purchaseAmount(),
                req.branchId(),
                req.reservationTableSlug(),
                req.reservationRecordId(),
                null, null, null,
                req.source() != null ? req.source() : "staff_app_scan",
                performedBy,
                req.notes()
            );

            var result = transactionService.record(input);

            return ResponseEntity.ok(new EarnResponse(
                result.transaction().getId(),
                customer.getCustomerHash(),
                result.card().getCurrentStamps(),
                result.card().getCurrentPoints(),
                result.card().getCashbackBalance(),
                result.stampsToReward(),
                buildEarnMessage(result)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[StaffApp] Error en earn", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Error interno"));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody ValidateRedemptionRequest req, Authentication auth) {
        if (req.redemptionCode() == null || req.redemptionCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "redemptionCode es requerido"));
        }
        String performedBy = extractPerformedBy(auth);
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
        String performedBy = extractPerformedBy(auth);
        var result = couponService.apply(
            req.code(),
            req.customerId(),
            req.purchaseAmount() == null ? BigDecimal.ZERO : req.purchaseAmount(),
            req.branchId(),
            performedBy
        );
        return ResponseEntity.ok(new CouponApplyResponse(
            result.accepted(),
            result.discountApplied(),
            result.reasonIfRejected()
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private LoyaltyCustomer resolveCustomer(EarnRequest req) {
        if (req.customerHash() != null && !req.customerHash().isBlank()) {
            return customerService.findByHash(req.customerHash()).orElse(null);
        }
        if (req.phone() != null && !req.phone().isBlank()) {
            return customerService.findByPhone(req.phone()).orElse(null);
        }
        return null;
    }

    private Long extractStaffId(Authentication auth) {
        if (auth == null) return null;
        String name = auth.getName();
        if (name == null || !name.startsWith("staff:")) return null;
        try {
            return Long.parseLong(name.substring("staff:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractPerformedBy(Authentication auth) {
        Long staffId = extractStaffId(auth);
        if (staffId == null) return "staff:anonymous";
        Optional<StaffUser> s = staffService.findById(staffId);
        if (s.isPresent()) {
            return "staff:" + staffId + " " + s.get().getName();
        }
        return "staff:" + staffId;
    }

    private String inferEarnType(EarnRequest req) {
        if (req.stamps() != null && req.stamps() != 0) return "stamp_earn";
        if (req.points() != null && req.points() != 0) return "points_earn";
        if (req.cashback() != null && req.cashback().signum() != 0) return "cashback_earn";
        return "stamp_earn";
    }

    private boolean canAffordReward(app.coincidir.api.marketing.domain.LoyaltyCard card,
                                    app.coincidir.api.marketing.domain.LoyaltyReward reward) {
        if (reward.getCostStamps() != null && reward.getCostStamps() > 0) {
            return card.getCurrentStamps() >= reward.getCostStamps();
        }
        if (reward.getCostPoints() != null && reward.getCostPoints() > 0) {
            return card.getCurrentPoints() >= reward.getCostPoints();
        }
        if (reward.getCostCashback() != null && reward.getCostCashback().signum() > 0) {
            return card.getCashbackBalance().compareTo(reward.getCostCashback()) >= 0;
        }
        return false;
    }

    private String buildEarnMessage(LoyaltyTransactionService.RecordResult r) {
        var t = r.transaction();
        StringBuilder sb = new StringBuilder();
        if (t.getStampsDelta() > 0)
            sb.append("¡Sumaste ").append(t.getStampsDelta()).append(" estampilla")
                .append(t.getStampsDelta() > 1 ? "s" : "").append("!");
        if (t.getPointsDelta() > 0)
            sb.append(sb.length() > 0 ? " " : "").append("Sumaste ").append(t.getPointsDelta()).append(" puntos.");
        if (t.getCashbackDelta() != null && t.getCashbackDelta().compareTo(BigDecimal.ZERO) > 0)
            sb.append(sb.length() > 0 ? " " : "").append("Sumaste $").append(t.getCashbackDelta()).append(" de cashback.");
        if (r.stampsToReward() > 0)
            sb.append(" Te faltan ").append(r.stampsToReward()).append(" para tu premio.");
        else if (r.stampsToReward() == 0)
            sb.append(" ¡Llegaste al premio! Podés canjearlo cuando quieras.");
        return sb.length() > 0 ? sb.toString() : "Acreditado correctamente.";
    }

    private static Map<String, Object> staffToDto(StaffUser s) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", s.getId());
        dto.put("name", s.getName());
        dto.put("role", s.getRole());
        return dto;
    }
}
