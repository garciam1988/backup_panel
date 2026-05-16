package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PublicWebPushController — Recibe del frontend la subscription de Web Push.
 *
 * La PWA pide permiso al navegador, obtiene el PushSubscription object y lo
 * envía acá. Lo guardamos como JSON crudo en loyalty_customer.web_push_subscription
 * para usarlo luego al enviar notificaciones.
 *
 * El navegador puede revocar el permiso o cambiar el endpoint en cualquier
 * momento; cada vez que la PWA detecta esto, vuelve a llamar acá. El front
 * llama DELETE cuando el usuario desactiva notificaciones desde la PWA.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/loyalty/card")
@RequiredArgsConstructor
public class PublicWebPushController {

    private final LoyaltyCustomerService customerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/{customerHash}/web-push-subscription")
    public ResponseEntity<?> subscribe(@PathVariable String customerHash,
                                       @RequestBody Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            customerService.saveWebPushSubscription(customerHash, json);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Error guardando web push subscription: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Subscription inválida"));
        }
    }

    @DeleteMapping("/{customerHash}/web-push-subscription")
    public ResponseEntity<?> unsubscribe(@PathVariable String customerHash) {
        try {
            customerService.saveWebPushSubscription(customerHash, null);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
