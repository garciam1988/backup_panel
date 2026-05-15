package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.dto.MarketingDtos.EnrollRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.EnrollResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.RedeemRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.RedemptionDto;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
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
    public ResponseEntity<?> enroll(@RequestBody EnrollRequest req, HttpServletRequest httpReq) {
        try {
            var res = customerService.enrollOrReactivate(new LoyaltyCustomerService.EnrollInput(
                req.phone(), req.firstName(), req.lastName(), req.email(),
                req.birthDate(), req.branchId(),
                req.source() != null ? req.source() : "qr",
                req.reservationTableSlug(), req.reservationRecordId()
            ));
            String hash = res.customer().getCustomerHash();
            String url = buildCardUrl(hash, httpReq);
            return ResponseEntity.ok(new EnrollResponse(
                res.customer().getId(), hash, url, res.alreadyExisted()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Construye la URL absoluta de la tarjeta del cliente. Estrategia:
     *  1) Header Origin del request (el navegador del cliente sabe en qué
     *     dominio está; es la fuente más confiable para multi-tenant).
     *  2) Header Referer (a veces presente cuando Origin no lo está).
     *  3) Env var MARKETING_PWA_BASE_URL (fallback estático).
     *  4) Path relativo "/c/{hash}" como último recurso.
     *
     * Esto evita que un backend compartido devuelva el dominio de OTRO
     * cliente (ej: bug donde Brasas Argentinas recibía links a Mikhuna).
     */
    private String buildCardUrl(String hash, HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin.replaceAll("/+$", "") + "/c/" + hash;
        }
        String referer = req.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI uri = URI.create(referer);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                int port = uri.getPort();
                if (scheme != null && host != null) {
                    String base = scheme + "://" + host + (port > 0 ? ":" + port : "");
                    return base + "/c/" + hash;
                }
            } catch (Exception ignored) { /* malformed referer, seguimos al fallback */ }
        }
        if (pwaBaseUrl != null && !pwaBaseUrl.isBlank()) {
            return pwaBaseUrl.replaceAll("/+$", "") + "/c/" + hash;
        }
        return "/c/" + hash;
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
