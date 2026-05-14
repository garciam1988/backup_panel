package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.NotificationLog;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.service.EmailTemplateService;
import app.coincidir.api.marketing.service.NotificationService;
import app.coincidir.api.marketing.service.TwilioWhatsAppService;
import app.coincidir.api.marketing.service.WebPushService;
import app.coincidir.api.marketing.util.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MarketingTestController — Endpoints para enviar mensajes de PRUEBA desde el
 * panel admin. Útil para:
 *
 *   1. Verificar configuración (Twilio / SMTP / VAPID) sin crear una campaña.
 *   2. Hacer demos a clientes (mandás un WhatsApp a un cliente real en vivo).
 *   3. Debuggear problemas: si el test funciona pero la campaña no, el
 *      problema está en el dispatcher, no en el sender.
 *
 * Todos los endpoints requieren JWT del panel admin.
 *
 * Importante: estos endpoints NO crean campañas ni se loguean en
 * NotificationLog de forma masiva — son envíos puntuales. Sí se loguean
 * a través del NotificationService para mantener trazabilidad.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing/test")
@RequiredArgsConstructor
public class MarketingTestController {

    private final TwilioWhatsAppService twilioService;
    private final NotificationService notificationService;
    private final WebPushService webPushService;
    private final EmailTemplateService emailTemplateService;
    private final LoyaltyCustomerRepository customerRepo;

    // ── Diagnóstico de configuración ─────────────────────────────────────

    /**
     * Devuelve el estado de configuración de cada canal de comunicación.
     * Útil para mostrar en el panel "Test de envíos" qué está OK y qué no.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("twilio", Map.of(
            "configured", twilioService.isConfigured(),
            "hint", twilioService.isConfigured()
                ? "OK. Listo para enviar WhatsApp."
                : "Faltan env vars TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_WHATSAPP_FROM"
        ));
        out.put("webPush", Map.of(
            "configured", webPushService.isConfigured(),
            "hint", webPushService.isConfigured()
                ? "OK. Las claves VAPID están cargadas."
                : "Faltan env vars VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY. Ver logs al arrancar para claves de muestra.",
            "publicKey", webPushService.isConfigured() ? webPushService.getPublicKey() : null
        ));
        out.put("smtp", Map.of(
            // No hay forma directa de verificar SMTP sin enviar; lo dejamos
            // como "asumido configurado" y que el cliente haga el test real.
            "configured", true,
            "hint", "Probá enviar un email de test para verificar."
        ));
        return ResponseEntity.ok(out);
    }

    // ── Test de WhatsApp ─────────────────────────────────────────────────

    /**
     * Envía un WhatsApp de prueba a un número. NO requiere que sea un cliente
     * enrolado — útil para probar Twilio con tu propio celular.
     *
     * Body: { "phone": "+541112345678", "message": "Hola test" }
     */
    @PostMapping("/whatsapp")
    public ResponseEntity<Map<String, Object>> testWhatsapp(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String message = body.getOrDefault("message", "Hola! Este es un mensaje de prueba desde el módulo Marketing.");

        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "phone es requerido"));
        }

        String normalized = PhoneNormalizer.normalize(phone);
        TwilioWhatsAppService.Result result = twilioService.send(normalized != null ? normalized : phone, message);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.accepted());
        out.put("phoneNormalized", normalized);
        out.put("messageSid", result.messageSid());
        out.put("twilioStatus", result.status());
        out.put("error", result.errorReason());
        return ResponseEntity.ok(out);
    }

    // ── Test de Email ────────────────────────────────────────────────────

    /**
     * Envía un email de prueba a una dirección. Si el email pertenece a un
     * cliente enrolado, usa la plantilla profesional con la marca; si no,
     * envía un email simple sin template.
     *
     * Body: { "email": "test@example.com", "subject": "...", "body": "..." }
     */
    @PostMapping("/email")
    public ResponseEntity<Map<String, Object>> testEmail(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String subject = body.getOrDefault("subject", "✉️ Email de prueba — Coincidir Marketing");
        String htmlBody = body.getOrDefault("body",
            "<p>Hola!</p><p>Este es un email de prueba desde el módulo Marketing.</p>" +
            "<p>Si lo recibís, significa que la configuración SMTP funciona correctamente. ✅</p>");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email es requerido"));
        }

        // Buscamos si el email pertenece a un cliente para usar la plantilla
        LoyaltyCustomer customer = customerRepo.findAll().stream()
            .filter(c -> email.equalsIgnoreCase(c.getEmail()))
            .findFirst()
            .orElse(null);

        try {
            NotificationLog result;
            if (customer != null) {
                // Usa template profesional con branding
                result = notificationService.sendEmailWithTemplate(
                    NotificationLog.SourceType.MANUAL, "test-email",
                    customer.getId(), subject, htmlBody, null, null);
            } else {
                // Email simple sin template (usuario no enrolado)
                // Necesitamos un customerId, así que skipeamos NotificationService
                // y usamos el mailSender directamente vía un método utilitario.
                return ResponseEntity.ok(Map.of(
                    "ok", false,
                    "error", "El email " + email + " no corresponde a ningún cliente enrolado. " +
                             "Para mandar test crea un cliente con ese email o probá con un email de cliente existente."
                ));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", result != null && result.getStatus() == NotificationLog.Status.SENT);
            out.put("status", result == null ? "NULL" : result.getStatus().name());
            out.put("error", result == null ? "Cliente no acepta email o no tiene email cargado" : result.getErrorMessage());
            out.put("usedTemplate", true);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("Error en test email", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Test de Web Push ─────────────────────────────────────────────────

    /**
     * Envía un Web Push de prueba a un cliente enrolado que tenga subscription
     * activa. Recibe el customerHash.
     *
     * Body: { "customerHash": "abc123", "title": "...", "body": "...", "url": "..." }
     */
    @PostMapping("/push")
    public ResponseEntity<Map<String, Object>> testPush(@RequestBody Map<String, String> body) {
        String hash = body.get("customerHash");
        String title = body.getOrDefault("title", "🔔 Notificación de prueba");
        String text = body.getOrDefault("body", "Si ves esto, las notificaciones push funcionan.");
        String url = body.getOrDefault("url", "/");

        if (hash == null || hash.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerHash es requerido"));
        }

        LoyaltyCustomer customer = customerRepo.findByCustomerHash(hash).orElse(null);
        if (customer == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado con ese hash"));
        }

        if (customer.getWebPushSubscription() == null || customer.getWebPushSubscription().isBlank()) {
            return ResponseEntity.ok(Map.of(
                "ok", false,
                "error", "El cliente no tiene subscription de push registrada. Debe activar push desde su tarjeta digital primero."
            ));
        }

        NotificationLog result = notificationService.queueWebPush(
            NotificationLog.SourceType.MANUAL, "test-push",
            customer.getId(), title, text, url);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result != null && result.getStatus() == NotificationLog.Status.SENT);
        out.put("status", result == null ? "NULL" : result.getStatus().name());
        out.put("error", result == null ? "Cliente no acepta push o no tiene subscription" : result.getErrorMessage());
        return ResponseEntity.ok(out);
    }
}
