package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.service.WebPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * VapidController — endpoint utilitario para generar claves VAPID al setup.
 *
 * GET /api/admin/marketing/vapid/generate
 *
 * Devuelve un par de claves nuevo cada vez. Copy-pasteás los valores a tus
 * env vars (VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY) y reiniciás el backend.
 *
 * IMPORTANTE: esto NO modifica la configuración runtime. Es solo un
 * generador. Las claves quedan en la respuesta HTTP y vos las copiás a
 * donde corresponda (Railway > Variables, .env.local, etc).
 *
 * También expone GET /vapid/public para que la PWA obtenga la public key
 * activa (la necesita para subscribeToPush).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing/vapid")
@RequiredArgsConstructor
public class VapidController {

    private final WebPushService webPushService;

    /**
     * Genera un par de claves VAPID nuevo. NO afecta la configuración runtime.
     * Para que las claves se usen, hay que setearlas como env vars y reiniciar.
     */
    @GetMapping("/generate")
    public ResponseEntity<?> generate() {
        try {
            WebPushService.GeneratedKeyPair kp = WebPushService.generateNewKeyPair();
            return ResponseEntity.ok(Map.of(
                "publicKey", kp.publicKey(),
                "privateKey", kp.privateKey(),
                "instructions", "Setealas como VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY (env vars) y reiniciá el backend. " +
                                "La pública también debe quedar accesible en GET /vapid/public para la PWA."
            ));
        } catch (Exception e) {
            log.error("Error generando claves VAPID", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
