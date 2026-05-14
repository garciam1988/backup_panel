package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.dto.MarketingDtos.CardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CustomerDto;
import app.coincidir.api.marketing.dto.MarketingDtos.ProgramDto;
import app.coincidir.api.marketing.dto.MarketingDtos.PublicCardView;
import app.coincidir.api.marketing.dto.MarketingDtos.RewardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.TransactionDto;
import app.coincidir.api.marketing.service.CouponService;
import app.coincidir.api.marketing.service.LoyaltyCardService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import app.coincidir.api.marketing.service.WebPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PublicLoyaltyCardController — Vista pública de la tarjeta del cliente.
 *
 * Accesible desde la PWA usando solo el customer_hash en la URL. NO requiere
 * JWT. La seguridad se basa en que el hash es opaco (21 chars URL-safe,
 * espacio 64^21) y solo lo conoce el cliente.
 *
 * URL pública: GET /api/public/loyalty/card/{customerHash}
 *
 * Devuelve un PublicCardView consolidado con todo lo que la PWA necesita
 * para renderizar la pantalla principal en una sola llamada:
 *   - Config del programa (colores, métodos, etc).
 *   - Datos del cliente.
 *   - Estado actual de la tarjeta.
 *   - Premios disponibles (filtrados por vigencia y stock).
 *   - Últimas transacciones (para el feed).
 */
@RestController
@RequestMapping("/api/public/loyalty")
@RequiredArgsConstructor
public class PublicLoyaltyCardController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyProgramService programService;
    private final LoyaltyRewardService rewardService;
    private final LoyaltyTransactionService transactionService;
    private final CouponService couponService;
    private final WebPushService webPushService;

    @GetMapping("/card/{customerHash}")
    public ResponseEntity<?> getCard(@PathVariable String customerHash) {
        return customerService.findByHash(customerHash).map(cust -> {
            var program = programService.getActiveProgram();
            var card = cardService.getOrCreate(cust);
            var rewards = rewardService.listAvailableNow(program.getId());
            var recent = transactionService.recent(cust.getId());

            return ResponseEntity.ok((Object) new PublicCardView(
                ProgramDto.fromEntity(program),
                CustomerDto.fromEntity(cust),
                CardDto.fromEntity(card),
                rewards.stream().map(RewardDto::fromEntity).toList(),
                recent.stream().map(TransactionDto::fromEntity).toList()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/card/{customerHash}/preferences")
    public ResponseEntity<?> updatePrefs(@PathVariable String customerHash,
                                         @RequestBody Map<String, Boolean> body) {
        try {
            LoyaltyCustomer updated = customerService.updateCommunicationPrefs(
                customerHash,
                body.get("acceptsWhatsapp"),
                body.get("acceptsEmail"),
                body.get("acceptsPush")
            );
            return ResponseEntity.ok(CustomerDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lista los cupones activos vigentes que el cliente puede usar.
     *
     * Estrategia MVP: devolvemos todos los cupones activos en este momento
     * (filtrados por validFrom/validUntil) que NO sean SINGLE_USE_GLOBAL
     * ya quemados. La PWA los muestra como códigos copiables; el mozo los
     * aplica desde Staff y el backend valida ahí los límites por cliente.
     *
     * No hace falta verificar cuál cliente tiene asignado cuál cupón porque
     * los cupones son "globales" en este modelo — cualquier cliente con el
     * código puede usarlo (con sus límites). En el futuro se puede agregar
     * coupon_assignment para cupones personalizados.
     */
    @GetMapping("/card/{customerHash}/coupons")
    public ResponseEntity<?> getCoupons(@PathVariable String customerHash) {
        if (customerService.findByHash(customerHash).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var active = couponService.listActiveNow();
        return ResponseEntity.ok(active.stream().map(CouponDto::fromEntity).toList());
    }

    /**
     * Devuelve la clave pública VAPID del servidor. La PWA la necesita
     * para llamar a pushManager.subscribe({applicationServerKey}).
     *
     * Endpoint público (sin auth) porque la PWA del cliente lo consume
     * antes de que el cliente acepte push. Si VAPID no está configurado,
     * devuelve 503 para que la PWA muestre un mensaje claro.
     */
    @GetMapping("/vapid/public-key")
    public ResponseEntity<?> getVapidPublicKey() {
        if (!webPushService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("error", "Web Push no configurado en este servidor"));
        }
        return ResponseEntity.ok(Map.of("publicKey", webPushService.getPublicKey()));
    }

    /**
     * Registra/actualiza la subscription de Web Push del cliente. Se
     * llama desde la PWA después de pushManager.subscribe() exitoso.
     *
     * Body: el JSON completo de la PushSubscription tal cual lo devuelve
     * el browser (formato estándar W3C: { endpoint, keys: { p256dh, auth } }).
     *
     * El backend guarda el JSON entero en loyalty_customer.web_push_subscription
     * y setea acceptsPush=true (porque si el cliente subscribió es porque
     * dio permiso explícito).
     */
    @PostMapping("/card/{customerHash}/push-subscription")
    public ResponseEntity<?> registerPushSubscription(@PathVariable String customerHash,
                                                      @RequestBody Map<String, Object> subscription) {
        try {
            String subscriptionJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(subscription);
            customerService.saveWebPushSubscription(customerHash, subscriptionJson);
            // Si el cliente subscribió es porque dio permiso explícito.
            // Actualizamos accepts_push=true para que las campañas le manden.
            customerService.updateCommunicationPrefs(customerHash, null, null, true);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Borra la subscription (cliente revocó permiso desde el navegador). */
    @DeleteMapping("/card/{customerHash}/push-subscription")
    public ResponseEntity<?> deletePushSubscription(@PathVariable String customerHash) {
        try {
            customerService.saveWebPushSubscription(customerHash, null);
            customerService.updateCommunicationPrefs(customerHash, null, null, false);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
