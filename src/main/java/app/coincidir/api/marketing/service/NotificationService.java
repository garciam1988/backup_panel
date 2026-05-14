package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.NotificationLog;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.NotificationLogRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * NotificationService — Dispatcher unificado de notificaciones multicanal.
 *
 * Centraliza el envío vía WhatsApp / Email / Web Push. Cada método:
 *   1. Crea un NotificationLog (status QUEUED) ANTES de intentar enviar.
 *   2. Llama al provider correspondiente.
 *   3. Actualiza el log a SENT/DELIVERED/FAILED según resultado.
 *
 * IMPLEMENTACIÓN BLOQUE 2 (mínima viable):
 *   - Email: real, usando JavaMailSender (ya configurado en el proyecto).
 *   - WhatsApp: stub. Se loguea como QUEUED. El envío real va por Bloque 7
 *     llamando al bot Node.js (que usa Twilio) vía HTTP.
 *   - Web Push: stub. Se loguea como QUEUED. El envío real requiere VAPID
 *     keys + librería web-push, que se agrega en Bloque 7.
 *
 * Esto permite que el resto del módulo (campañas, triggers, etc) funcione
 * end-to-end con email real, y los otros canales se llenen sin tocar
 * controllers ni schedulers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository logRepo;
    private final LoyaltyCustomerRepository customerRepo;
    private final JavaMailSender mailSender;
    private final TwilioWhatsAppService twilioService;
    private final EmailTemplateService emailTemplateService;
    private final WebPushService webPushService;

    @Value("${spring.mail.username:noreply@coincidir.app}")
    private String mailFrom;

    // ── EMAIL ────────────────────────────────────────────────────────────

    /**
     * Envía un email transaccional simple. El htmlBody se manda TAL CUAL,
     * sin envolver en plantilla. Útil para emails internos / transaccionales
     * que no necesitan branding (ej: confirmación de enrolamiento).
     *
     * Si necesitás un email con header de marca + CTA + cupón, usá
     * {@link #sendEmailWithTemplate} en su lugar.
     *
     * Si accepts_email=false, NO se envía y devuelve null.
     */
    @Transactional
    public NotificationLog sendEmail(NotificationLog.SourceType sourceType, String sourceRef,
                                     Long customerId, String subject, String htmlBody) {
        return doSendEmail(sourceType, sourceRef, customerId, subject, htmlBody);
    }

    /**
     * Envía un email envuelto en la plantilla profesional del módulo Marketing:
     * header con logo + brand name + secondaryColor, saludo personalizado,
     * cuerpo HTML, botón CTA opcional, caja de cupón opcional, footer.
     *
     * Lo que `bodyHtml` debería tener es solo el mensaje del operador (el
     * texto entre saludo y CTA). El resto lo arma EmailTemplateService.
     *
     * @param bodyHtml    HTML del mensaje (sin <html>, sin <body>)
     * @param ctaUrl      URL del botón "Ver más" (null → sin botón)
     * @param coupon      cupón asociado (null → sin caja de cupón)
     */
    @Transactional
    public NotificationLog sendEmailWithTemplate(NotificationLog.SourceType sourceType, String sourceRef,
                                                 Long customerId, String subject, String bodyHtml,
                                                 String ctaUrl,
                                                 app.coincidir.api.marketing.domain.Coupon coupon) {
        LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return null;
        if (customer.getEmail() == null || customer.getEmail().isBlank()) return null;
        if (!Boolean.TRUE.equals(customer.getAcceptsEmail())) {
            log.debug("Saltando email (template) a customer={} (accepts_email=false)", customerId);
            return null;
        }
        String fullHtml = emailTemplateService.render(customer, bodyHtml, ctaUrl, coupon);
        return doSendEmail(sourceType, sourceRef, customerId, subject, fullHtml);
    }

    /** Implementación común del envío SMTP. */
    private NotificationLog doSendEmail(NotificationLog.SourceType sourceType, String sourceRef,
                                        Long customerId, String subject, String htmlBody) {
        LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return null;
        if (customer.getEmail() == null || customer.getEmail().isBlank()) return null;
        if (!Boolean.TRUE.equals(customer.getAcceptsEmail())) {
            log.debug("Saltando email a customer={} (accepts_email=false)", customerId);
            return null;
        }

        NotificationLog n = newLog(sourceType, sourceRef, customerId,
            NotificationLog.Channel.EMAIL, subject, htmlBody, null);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(customer.getEmail());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);

            n.setStatus(NotificationLog.Status.SENT);
            n.setSentAt(Instant.now());
            n.setProvider("smtp");
            log.info("Email enviado customer={} subject={}", customerId, subject);
        } catch (Exception e) {
            log.warn("Falló envío de email a customer={}: {}", customerId, e.getMessage());
            n.setStatus(NotificationLog.Status.FAILED);
            n.setErrorMessage(e.getMessage());
        }
        return logRepo.save(n);
    }

    // ── WHATSAPP ─────────────────────────────────────────────────────────

    /**
     * Envía un WhatsApp al cliente vía Twilio. Si Twilio no está configurado,
     * el log queda en QUEUED con un mensaje indicando la razón (para que el
     * operador pueda investigar). Si Twilio responde con error 4xx/5xx, queda
     * FAILED con el mensaje devuelto por Twilio (típicamente: "fuera de
     * ventana de 24h", "número no es WhatsApp válido", etc).
     *
     * Respeta accepts_whatsapp del cliente — si está false, no envía y
     * devuelve null sin loguear (es responsabilidad del caller filtrar
     * contra preferencias).
     */
    @Transactional
    public NotificationLog queueWhatsapp(NotificationLog.SourceType sourceType, String sourceRef,
                                         Long customerId, String body) {
        LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return null;
        if (customer.getPhone() == null || customer.getPhone().isBlank()) return null;
        if (!Boolean.TRUE.equals(customer.getAcceptsWhatsapp())) {
            log.debug("Saltando WhatsApp a customer={} (accepts_whatsapp=false)", customerId);
            return null;
        }

        NotificationLog n = newLog(sourceType, sourceRef, customerId,
            NotificationLog.Channel.WHATSAPP, null, body, null);
        n.setProvider("twilio");

        // Si Twilio no está configurado, dejamos QUEUED y registramos el motivo
        // en error_message para que sea visible. Esto permite a entornos sin
        // Twilio seguir funcionando sin romper el flujo del módulo.
        if (!twilioService.isConfigured()) {
            n.setStatus(NotificationLog.Status.QUEUED);
            n.setErrorMessage("Twilio no configurado (faltan TWILIO_ACCOUNT_SID/AUTH_TOKEN/WHATSAPP_FROM)");
            log.info("WhatsApp queued (Twilio no configurado) → customer={}", customerId);
            return logRepo.save(n);
        }

        // Twilio configurado: enviamos en el momento. El dispatcher batchea de
        // a 50 y este sender es cheap (1 HTTP request), así que no hay riesgo
        // de bloquear la transacción.
        TwilioWhatsAppService.Result result = twilioService.send(customer.getPhone(), body);
        if (result.accepted()) {
            n.setStatus(NotificationLog.Status.SENT);
            n.setProviderMessageId(result.messageSid());
            n.setSentAt(Instant.now());
        } else {
            n.setStatus(NotificationLog.Status.FAILED);
            n.setErrorMessage(result.errorReason());
        }
        return logRepo.save(n);
    }

    // ── WEB PUSH ─────────────────────────────────────────────────────────

    /**
     * Envía un Web Push al cliente. La PWA del cliente tuvo que haber
     * registrado su subscription previamente vía POST /push-subscription.
     *
     * Si VAPID no está configurado en el servidor, el log queda en QUEUED
     * con el motivo. Si la subscription expiró (HTTP 410 / 404 del Push
     * Service), borramos la subscription del customer y marcamos FAILED.
     * Si el Push Service responde otro error, también FAILED con el motivo.
     *
     * Respeta accepts_push del cliente — si está false, no envía y devuelve
     * null sin loguear.
     */
    @Transactional
    public NotificationLog queueWebPush(NotificationLog.SourceType sourceType, String sourceRef,
                                        Long customerId, String title, String body, String url) {
        LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return null;
        if (customer.getWebPushSubscription() == null || customer.getWebPushSubscription().isBlank()) return null;
        if (!Boolean.TRUE.equals(customer.getAcceptsPush())) {
            log.debug("Saltando push a customer={} (accepts_push=false)", customerId);
            return null;
        }

        // Payload que recibe el Service Worker en el evento 'push'.
        // El SW lo parsea y muestra la notificación nativa.
        String payloadJson = buildPushPayload(title, body, url);

        NotificationLog n = newLog(sourceType, sourceRef, customerId,
            NotificationLog.Channel.WEB_PUSH, title, body, payloadJson);
        n.setProvider("webpush");

        if (!webPushService.isConfigured()) {
            n.setStatus(NotificationLog.Status.QUEUED);
            n.setErrorMessage("Web Push no configurado (faltan VAPID_PUBLIC_KEY/PRIVATE_KEY)");
            log.info("Web Push queued (VAPID no configurado) → customer={}", customerId);
            return logRepo.save(n);
        }

        WebPushService.Result result = webPushService.send(customer.getWebPushSubscription(), payloadJson);
        if (result.accepted()) {
            n.setStatus(NotificationLog.Status.SENT);
            n.setSentAt(Instant.now());
        } else {
            n.setStatus(NotificationLog.Status.FAILED);
            n.setErrorMessage(result.errorReason());
            // Si la subscription expiró, la limpiamos del customer para no
            // seguir intentando enviarle.
            if (result.expired()) {
                log.info("Subscription expirada para customer={}, limpiando", customerId);
                customer.setWebPushSubscription(null);
                customerRepo.save(customer);
            }
        }
        return logRepo.save(n);
    }

    /** Arma el payload JSON que va a leer el Service Worker. */
    private String buildPushPayload(String title, String body, String url) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"title\":\"").append(jsonEscape(title)).append("\",");
        sb.append("\"body\":\"").append(jsonEscape(body)).append("\"");
        if (url != null && !url.isBlank()) {
            sb.append(",\"url\":\"").append(jsonEscape(url)).append("\"");
        }
        sb.append('}');
        return sb.toString();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private NotificationLog newLog(NotificationLog.SourceType sourceType, String sourceRef,
                                   Long customerId, NotificationLog.Channel channel,
                                   String title, String body, String payloadJson) {
        NotificationLog n = new NotificationLog();
        n.setSourceType(sourceType);
        n.setSourceRef(sourceRef);
        n.setCustomerId(customerId);
        n.setChannel(channel);
        n.setTitle(title);
        n.setBody(body);
        n.setPayloadJson(payloadJson);
        return n;
    }

    /** Reemplaza variables {firstName}, {phone}, etc en un template. */
    public String renderTemplate(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String out = template;
        if (vars != null) {
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                String val = e.getValue() == null ? "" : e.getValue().toString();
                out = out.replace("{" + e.getKey() + "}", val);
            }
        }
        return out;
    }
}
