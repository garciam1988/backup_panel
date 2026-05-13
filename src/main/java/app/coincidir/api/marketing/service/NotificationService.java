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

    @Value("${spring.mail.username:noreply@coincidir.app}")
    private String mailFrom;

    // ── EMAIL ────────────────────────────────────────────────────────────

    /**
     * Envía un email transaccional/automatización al cliente. Si el cliente
     * tiene accepts_email=false, NO se envía y devuelve null. El consentimiento
     * es responsabilidad del caller; acá garantizamos no enviar contra preferencia.
     */
    @Transactional
    public NotificationLog sendEmail(NotificationLog.SourceType sourceType, String sourceRef,
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
     * Encola un mensaje de WhatsApp. El envío real lo hace el bot Node.js
     * (vía Twilio) en el Bloque 7. Por ahora dejamos el registro en QUEUED.
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
        n.setStatus(NotificationLog.Status.QUEUED);
        n.setProvider("twilio");
        return logRepo.save(n);
    }

    // ── WEB PUSH ─────────────────────────────────────────────────────────

    /**
     * Encola un Web Push. Envío real en Bloque 7 (con VAPID + web-push lib).
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

        NotificationLog n = newLog(sourceType, sourceRef, customerId,
            NotificationLog.Channel.WEB_PUSH, title, body,
            "{\"url\":\"" + (url == null ? "" : url.replace("\"", "\\\"")) + "\"}");
        n.setStatus(NotificationLog.Status.QUEUED);
        n.setProvider("webpush");
        return logRepo.save(n);
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
