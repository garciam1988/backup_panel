package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.dto.MarketingDtos.EnrollRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.EnrollResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.RedeemRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.RedemptionDto;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * PublicLoyaltyEnrollController — Endpoints públicos para enrolamiento y
 * solicitud de canjes desde la PWA del cliente o desde el bot.
 *
 *  POST /api/public/loyalty/enroll               { phone, firstName, ... }
 *      Crea o reactiva el cliente. Devuelve customerHash y URL de la PWA.
 *      Idempotente: si el phone ya existe, devuelve el existente.
 *
 *  POST /api/public/loyalty/card/{hash}/redeem   { rewardId }
 *      Solicita canje de un premio. Devuelve redemption_code.
 */
@RestController
@RequestMapping("/api/public/loyalty")
@RequiredArgsConstructor
public class PublicLoyaltyEnrollController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyRedemptionService redemptionService;
    private final LoyaltyRewardService rewardService;

    @Value("${marketing.pwa-base-url:}")
    private String pwaBaseUrl;

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@RequestBody EnrollRequest req) {
        try {
            var res = customerService.enrollOrReactivate(new LoyaltyCustomerService.EnrollInput(
                req.phone(), req.firstName(), req.lastName(), req.email(),
                req.birthDate(), req.branchId(),
                req.source() != null ? req.source() : "qr",
                req.reservationTableSlug(), req.reservationRecordId()
            ));
            String hash = res.customer().getCustomerHash();
            String url = pwaBaseUrl == null || pwaBaseUrl.isBlank()
                ? "/c/" + hash
                : pwaBaseUrl.replaceAll("/+$", "") + "/c/" + hash;
            return ResponseEntity.ok(new EnrollResponse(
                res.customer().getId(), hash, url, res.alreadyExisted()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/card/{customerHash}/redeem")
    public ResponseEntity<?> redeem(@PathVariable String customerHash, @RequestBody RedeemRequest req) {
        var custOpt = customerService.findByHash(customerHash);
        if (custOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (req == null || req.rewardId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "rewardId requerido"));
        }
        var result = redemptionService.request(custOpt.get().getId(), req.rewardId(), null);
        if (!result.accepted()) {
            return ResponseEntity.status(409).body(Map.of(
                "error", result.reasonIfRejected(),
                "accepted", false));
        }
        var r = result.redemption();
        // Obtenemos nombre del reward (lookup ligero)
        String rewardName = rewardService.findById(r.getRewardId()).map(rr -> rr.getName()).orElse(null);
        return ResponseEntity.ok(new RedemptionDto(
            r.getId(), r.getCustomerId(), r.getRewardId(), rewardName,
            r.getRedemptionCode(), r.getStampsCost(), r.getPointsCost(),
            r.getCashbackCost() == null ? BigDecimal.ZERO : r.getCashbackCost(),
            r.getStatus(), r.getRequestedAt(), r.getExpiresAt(),
            r.getRedeemedAt(), r.getRedeemedBranch()
        ));
    }
}
