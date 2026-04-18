package app.coincidir.api.service;

import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupDestinationTransferService;
import app.coincidir.api.domain.GroupFerryService;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.GroupTransferService;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.notification.NotificationChannel;
import app.coincidir.api.domain.notification.NotificationType;
import app.coincidir.api.domain.notification.UserNotification;
import app.coincidir.api.repository.*;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceEmittedNotificationService {

    private final JavaMailSender mailSender;
    private final TravelRequestRepository travelRequestRepo;
    private final UserNotificationRepository notificationRepo;

    private final GroupFerryServiceRepository ferryRepo;
    private final GroupTransferServiceRepository transferRepo;
    private final GroupDestinationTransferServiceRepository destinationTransferRepo;
    private final GroupAccommodationServiceRepository accommodationRepo;
    private final GroupAirServiceRepository airRepo;

    @Value("${coincidir.mail-from:${coincidir.sales-email:no-reply@coincidir.com}}")
    private String mailFrom;

    @Value("${coincidir.user-portal-url:${coincidir.portal-url:http://localhost:3000}}")
    private String userPortalUrl;

    @Value("${coincidir.mail-bcc:}")
    private String mailBcc;

    @Transactional
    public void notifyServiceEmitted(Long groupId, GroupServiceMenuItem menuItem) {
        if (groupId == null || menuItem == null) return;

        ServiceCode code = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        String serviceLabel = menuItem.getDisplayName() != null ? menuItem.getDisplayName() : (code != null ? code.name() : "Servicio");

        String link = buildGroupLink(groupId);

        String subject = "Coincidir - Se emitió voucher " + serviceLabel;
        String detailsText = buildDetailsText(groupId, menuItem, code);
        String html = renderEmailHtml(serviceLabel, detailsText, link);

        // destinatarios: emails únicos válidos de las solicitudes asociadas al grupo
        // (evita fallos parciales de SMTP por emails inválidos que terminan generando reintentos y mails duplicados)
        List<TravelRequest> requests = travelRequestRepo.findByGroupIdOrderByIdAsc(groupId);
        LinkedHashSet<String> recipients = collectValidRecipientEmails(requests);

        if (recipients.isEmpty()) return;

        // Registrar 1 notificación por destinatario (para campana en user_panel)
        List<UserNotification> notifications = new ArrayList<>();
        for (String email : recipients) {
            UserNotification n = new UserNotification();
            n.setRecipientEmail(email);
            n.setType(NotificationType.SERVICE_EMITTED);
            n.setChannel(NotificationChannel.EMAIL);
            n.setGroupId(groupId);
            n.setMenuItemId(menuItem.getId());
            n.setServiceCode(code != null ? code.name() : null);
            n.setServiceLabel(serviceLabel);
            n.setSubject(subject);
            n.setMessage(detailsText);
            n.setLinkUrl(link);
            notifications.add(notificationRepo.save(n));
        }

        // Enviar email. Prioridad: 1 solo mail a todos. Si el SMTP lo bloquea, fallback individual.
        try {
            SendReport report = sendEmailWithReport(recipients, subject, html);
            for (UserNotification n : notifications) {
                String err = report.errorsByRecipient.get(n.getRecipientEmail());
                if (err == null) {
                    n.setSentAt(report.sentAt);
                    n.setSendError(null);
                } else {
                    n.setSendError(err);
                }
            }
        } catch (Exception ex) {
            String err = safeTruncate(ex.getMessage(), 2000);
            log.warn("No se pudo enviar email de servicio emitido. groupId={}, menuItemId={}, error={}", groupId, menuItem.getId(), err);
            for (UserNotification n : notifications) {
                n.setSendError(err);
            }
        }

        notificationRepo.saveAll(notifications);
    }

    /**
     * Notifica por email cuando un servicio pasa a estado PAGADO.
     * Se utiliza principalmente para: ALOJAMIENTOS, TRASLADOS, TRASLADOS_DESTINO.
     */
    @Transactional
    public void notifyServicePaid(Long groupId, GroupServiceMenuItem menuItem) {
        if (groupId == null || menuItem == null) return;

        ServiceCode code = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        String serviceLabel = menuItem.getDisplayName() != null ? menuItem.getDisplayName() : (code != null ? code.name() : "Servicio");

        String link = buildGroupLink(groupId);

        String subject = "Coincidir - Se emitió voucher " + serviceLabel;
        String detailsText = buildDetailsText(groupId, menuItem, code);
        String html = renderPaidEmailHtml(serviceLabel, detailsText, link);

        List<TravelRequest> requests = travelRequestRepo.findByGroupIdOrderByIdAsc(groupId);
        LinkedHashSet<String> recipients = collectValidRecipientEmails(requests);

        if (recipients.isEmpty()) return;

        // Registrar 1 notificación por destinatario (para campana en user_panel)
        List<UserNotification> notifications = new ArrayList<>();
        for (String email : recipients) {
            UserNotification n = new UserNotification();
            n.setRecipientEmail(email);
            n.setType(NotificationType.SERVICE_PAID);
            n.setChannel(NotificationChannel.EMAIL);
            n.setGroupId(groupId);
            n.setMenuItemId(menuItem.getId());
            n.setServiceCode(code != null ? code.name() : null);
            n.setServiceLabel(serviceLabel);
            n.setSubject(subject);
            n.setMessage(detailsText);
            n.setLinkUrl(link);
            notifications.add(notificationRepo.save(n));
        }

        try {
            SendReport report = sendEmailWithReport(recipients, subject, html);
            for (UserNotification n : notifications) {
                String err = report.errorsByRecipient.get(n.getRecipientEmail());
                if (err == null) {
                    n.setSentAt(report.sentAt);
                    n.setSendError(null);
                } else {
                    n.setSendError(err);
                }
            }
        } catch (Exception ex) {
            String err = safeTruncate(ex.getMessage(), 2000);
            log.warn("No se pudo enviar email de servicio pagado. groupId={}, menuItemId={}, error={}", groupId, menuItem.getId(), err);
            for (UserNotification n : notifications) {
                n.setSendError(err);
            }
        }

        notificationRepo.saveAll(notifications);
    }

    private record SendReport(Instant sentAt, Map<String, String> errorsByRecipient) {}

    private static LinkedHashSet<String> collectValidRecipientEmails(List<TravelRequest> requests) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (requests == null || requests.isEmpty()) return out;

        for (TravelRequest tr : requests) {
            String raw = tr != null ? tr.getEmail() : null;
            if (raw == null || raw.isBlank()) continue;

            // Soporta que por error venga "a@b.com, c@d.com" en el campo email.
            try {
                InternetAddress[] parsed = InternetAddress.parse(raw, false);
                for (InternetAddress ia : parsed) {
                    String addr = ia != null ? ia.getAddress() : null;
                    if (addr == null || addr.isBlank()) continue;
                    String norm = addr.trim().toLowerCase();
                    if (isValidEmail(norm)) out.add(norm);
                }
            } catch (Exception ex) {
                // Si el email no es parseable, se ignora (para no romper el envío a los demás)
            }
        }
        return out;
    }

    private static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) return false;
        try {
            InternetAddress ia = new InternetAddress(email);
            ia.validate();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Envía 1 solo mail por servicio emitido (un único envío SMTP).
     *
     * Estrategia: todos los destinatarios en TO (un único envío SMTP).
     * Mantiene el comportamiento anterior (visible para todos) pero evita mails duplicados.
     *
     * Devuelve un reporte con fecha de envío (si al menos 1 destinatario fue entregado) y errores por destinatario.
     */
    private SendReport sendEmailWithReport(Collection<String> recipients, String subject, String html) throws Exception {
        if (recipients == null || recipients.isEmpty()) return new SendReport(null, Map.of());

        LinkedHashSet<String> all = new LinkedHashSet<>(recipients);
        Map<String, String> errors = new HashMap<>();

        try {
            // 1 solo envío: todos en TO
            sendMail(all.toArray(new String[0]), null, subject, html);
            return new SendReport(Instant.now(), errors);
        } catch (Exception ex) {
            // Si hubo entrega parcial, marcamos qué direcciones fallaron y evitamos reintentos.
            PartialDelivery p = extractPartialDelivery(ex, all);
            if (p != null) {
                for (String inv : p.invalid) {
                    errors.putIfAbsent(inv, "Destinatario inválido");
                }
                for (String unsent : p.unsent) {
                    errors.putIfAbsent(unsent, safeTruncate(ex.getMessage(), 2000));
                }

                // Si no sabemos exactamente cuáles fallaron, marcamos todo lo que no esté en 'sent'.
                for (String r : all) {
                    if (!p.sent.contains(r) && !errors.containsKey(r)) {
                        errors.put(r, safeTruncate(ex.getMessage(), 2000));
                    }
                }

                Instant sentAt = p.sent.isEmpty() ? null : Instant.now();
                return new SendReport(sentAt, errors);
            }

            throw ex;
        }
    }

    /**
     * Si JavaMail lanza excepción pero llegó a entregar a algunos destinatarios (validSentAddresses),
     * evita reintentos a esos recipients (para no duplicar mails).
     */
    private record PartialDelivery(Set<String> sent, Set<String> unsent, Set<String> invalid) {}

    private static PartialDelivery extractPartialDelivery(Exception ex, Set<String> recipients) {
        if (ex == null || recipients == null || recipients.isEmpty()) return null;

        SendFailedException sfe = findSendFailedException(ex);
        if (sfe == null) return null;

        Set<String> sent = normalizeAddresses(sfe.getValidSentAddresses());
        Set<String> unsent = normalizeAddresses(sfe.getValidUnsentAddresses());
        Set<String> invalid = normalizeAddresses(sfe.getInvalidAddresses());

        // Dejamos sólo direcciones que realmente estaban en el set de destinatarios
        sent.retainAll(recipients);
        unsent.retainAll(recipients);
        invalid.retainAll(recipients);

        return new PartialDelivery(sent, unsent, invalid);
    }

    private static Set<String> normalizeAddresses(Address[] addrs) {
        Set<String> out = new LinkedHashSet<>();
        if (addrs == null) return out;
        for (Address a : addrs) {
            String addr = normalizeAddress(a);
            if (addr != null) out.add(addr);
        }
        return out;
    }

    private static String normalizeAddress(Address a) {
        if (a == null) return null;
        String s = a.toString();
        if (s == null) return null;
        // A veces viene "Nombre <mail@dom.com>". Nos quedamos con el address.
        try {
            InternetAddress[] parsed = InternetAddress.parse(s, false);
            if (parsed.length == 0) return null;
            String addr = parsed[0].getAddress();
            if (addr == null || addr.isBlank()) return null;
            return addr.trim().toLowerCase();
        } catch (Exception ignored) {
            return s.trim().toLowerCase();
        }
    }

    private static SendFailedException findSendFailedException(Throwable t) {
        if (t == null) return null;
        if (t instanceof SendFailedException sfe) return sfe;

        // Spring puede envolver en MailSendException
        if (t instanceof MailSendException mse && mse.getFailedMessages() != null) {
            for (Exception inner : mse.getFailedMessages().values()) {
                SendFailedException found = findSendFailedException(inner);
                if (found != null) return found;
            }
        }

        // Jakarta mail puede encadenar nextException
        if (t instanceof MessagingException me) {
            Exception next = me.getNextException();
            SendFailedException foundNext = findSendFailedException(next);
            if (foundNext != null) return foundNext;
        }

        return findSendFailedException(t.getCause());
    }

    private void sendMail(String[] to, String[] bcc, String subject, String html) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, "utf-8");
        if (mailFrom != null && !mailFrom.isBlank()) {
            helper.setFrom(mailFrom);
        }
        helper.setTo(to);
        java.util.LinkedHashSet<String> bccSet = new java.util.LinkedHashSet<>();
        if (bcc != null) {
            for (String v : bcc) {
                if (v != null && !v.isBlank()) bccSet.add(v.trim());
            }
        }
        if (mailBcc != null && !mailBcc.isBlank()) {
            bccSet.add(mailBcc.trim());
        }
        if (!bccSet.isEmpty()) {
            helper.setBcc(bccSet.toArray(new String[0]));
        }
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(msg);
    }

    private String buildGroupLink(Long groupId) {
        String base = userPortalUrl == null ? "" : userPortalUrl.trim();
        if (base.endsWith("/")) base = base.replaceAll("/+$", "");
        if (base.isBlank()) return "/grupo/" + groupId;
        return base + "/grupo/" + groupId;
    }

    private String buildDetailsText(Long groupId, GroupServiceMenuItem menuItem, ServiceCode code) {
        List<String> lines = new ArrayList<>();
        lines.add("Grupo #" + groupId);

        if (code == null) {
            lines.add("Servicio: " + (menuItem.getDisplayName() != null ? menuItem.getDisplayName() : ""));
            return String.join("\n", lines);
        }

        switch (code) {
            case FERRY -> {
                GroupFerryService s = ferryRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (s != null) {
                    lines.add("Servicio: Ferry");
                    if (notBlank(s.getOriginPort()) || notBlank(s.getDestinationPort())) {
                        lines.add("Ruta: " + safe(s.getOriginPort()) + " → " + safe(s.getDestinationPort()));
                    }
                    if (s.getDepartureDate() != null) lines.add("Salida: " + s.getDepartureDate());
                    if (s.getReturnDate() != null) lines.add("Regreso: " + s.getReturnDate());
                    if (notBlank(s.getFerryCompany())) lines.add("Compañía: " + s.getFerryCompany());
                    if (s.getDepartureTime() != null) lines.add("Hora salida: " + s.getDepartureTime());
                    if (s.getReturnTime() != null) lines.add("Hora regreso: " + s.getReturnTime());
                }
            }
            case AEREOS -> {
                GroupAirService s = airRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (s != null) {
                    lines.add("Servicio: Aéreos");
                    if (notBlank(s.getAirline())) lines.add("Aerolínea: " + s.getAirline());
                    if (notBlank(s.getOrigin()) || notBlank(s.getDestination())) {
                        lines.add("Ruta: " + safe(s.getOrigin()) + " → " + safe(s.getDestination()));
                    }
                    if (s.getDepartureDate() != null) lines.add("Salida: " + s.getDepartureDate());
                    if (s.getDepartureTime() != null) lines.add("Hora salida: " + s.getDepartureTime());
                    if (s.getReturnDate() != null) lines.add("Regreso: " + s.getReturnDate());
                    if (s.getReturnDepartureTime() != null) lines.add("Hora regreso: " + s.getReturnDepartureTime());
                }
            }
            case ALOJAMIENTOS -> {
                GroupAccommodationService s = accommodationRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (s != null) {
                    lines.add("Servicio: Alojamiento");
                    if (notBlank(s.getName())) lines.add("Nombre: " + s.getName());
                    if (notBlank(s.getCity()) || notBlank(s.getCountry())) {
                        lines.add("Ubicación: " + safe(s.getCity()) + (notBlank(s.getCountry()) ? ", " + s.getCountry() : ""));
                    }
                    if (s.getCheckInDate() != null) lines.add("Check-in: " + s.getCheckInDate());
                    if (s.getCheckOutDate() != null) lines.add("Check-out: " + s.getCheckOutDate());
                    if (s.getCheckInTime() != null) lines.add("Hora check-in: " + s.getCheckInTime());
                    if (s.getCheckOutTime() != null) lines.add("Hora check-out: " + s.getCheckOutTime());
                    if (s.getRegimen() != null) lines.add("Régimen: " + s.getRegimen().name());
                }
            }
            case TRASLADOS -> {
                GroupTransferService s = transferRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (s != null) {
                    lines.add("Servicio: Traslados BA");
                    if (notBlank(s.getPickupPlace()) || notBlank(s.getDestinationPlace())) {
                        lines.add("Trayecto: " + safe(s.getPickupPlace()) + " → " + safe(s.getDestinationPlace()));
                    }
                    if (s.getDepartureDate() != null) lines.add("Fecha: " + s.getDepartureDate());
                    if (s.getDepartureTime() != null) lines.add("Hora: " + s.getDepartureTime());
                }
            }
            case TRASLADOS_DESTINO -> {
                GroupDestinationTransferService s = destinationTransferRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (s != null) {
                    lines.add("Servicio: Traslados Destino");
                    if (notBlank(s.getPickupPlace()) || notBlank(s.getDestinationPlace())) {
                        lines.add("Trayecto: " + safe(s.getPickupPlace()) + " → " + safe(s.getDestinationPlace()));
                    }
                    if (s.getDepartureDate() != null) lines.add("Fecha: " + s.getDepartureDate());
                    if (s.getDepartureTime() != null) lines.add("Hora: " + s.getDepartureTime());
                }
            }
        }

        if (lines.size() == 1) {
            // fallback
            lines.add("Servicio: " + serviceLabelForFallback(menuItem, code));
        }

        return String.join("\n", lines);
    }

    private String serviceLabelForFallback(GroupServiceMenuItem menuItem, ServiceCode code) {
        if (menuItem != null && menuItem.getDisplayName() != null && !menuItem.getDisplayName().isBlank()) {
            return menuItem.getDisplayName();
        }
        return code != null ? code.name() : "Servicio";
    }

    private String renderEmailHtml(String serviceLabel, String detailsText, String link) {
        String now = LocalDateTime.now().toString();
        String escapedDetails = esc(detailsText).replace("\n", "<br/>");
        String linkHtml = link != null && !link.isBlank()
                ? ("<a href=\"" + esc(link) + "\" style=\"display:inline-block;background:#ff4d00;color:#fff;text-decoration:none;font-weight:800;padding:12px 16px;border-radius:12px;\">Ver en Coincidir</a>")
                : "";

        return ("""
<!doctype html>
<html>
<head><meta charset=\"utf-8\"/></head>
<body style=\"font-family:Inter,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#f8f8f8;margin:0;padding:24px;color:#222;\">
  <div style=\"max-width:640px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb;\">
    <div style=\"background:linear-gradient(135deg,#ff7a1a,#ff4d00);padding:20px 24px;color:#fff;\">
      <div style=\"margin:0;font-size:20px;font-weight:800;letter-spacing:0.2px;\">Voucher emitido</div>
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">%s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Se emitió el voucher del servicio <b>%s</b>.</p>

      <div style=\"border:1px solid #e5e7eb;border-radius:12px;padding:14px;background:#fafafa;color:#111827;line-height:1.45;\">
        %s
      </div>

      <div style=\"margin-top:16px;\">%s</div>

      <p style=\"margin:16px 0 0;color:#6b7280;font-size:12px;\">
        Si no esperabas este mensaje, podes ignorarlo.
      </p>
    </div>

    <div style=\"padding:18px 24px;font-size:12px;color:#6b7280;border-top:1px solid #f3f3f3;background:#fafafa;\">
      Coincidir
    </div>
  </div>
</body>
</html>
""").formatted(
                esc(now),
                esc(serviceLabel),
                escapedDetails,
                linkHtml
        );
    }

    private String renderPaidEmailHtml(String serviceLabel, String detailsText, String link) {
        String now = LocalDateTime.now().toString();
        String escapedDetails = esc(detailsText).replace("\n", "<br/>");
        String linkHtml = link != null && !link.isBlank()
                ? ("<a href=\"" + esc(link) + "\" style=\"display:inline-block;background:#ff4d00;color:#fff;text-decoration:none;font-weight:800;padding:12px 16px;border-radius:12px;\">Ver en Coincidir</a>")
                : "";

        return ("""
<!doctype html>
<html>
<head><meta charset=\"utf-8\"/></head>
<body style=\"font-family:Inter,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#f8f8f8;margin:0;padding:24px;color:#222;\">
  <div style=\"max-width:640px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb;\">
    <div style=\"background:linear-gradient(135deg,#ff7a1a,#ff4d00);padding:20px 24px;color:#fff;\">
      <div style=\"margin:0;font-size:20px;font-weight:800;letter-spacing:0.2px;\">Voucher emitido</div>
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">%s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Se emitió el voucher del servicio <b>%s</b>.</p>

      <div style=\"border:1px solid #e5e7eb;border-radius:12px;padding:14px;background:#fafafa;color:#111827;line-height:1.45;\">
        %s
      </div>

      <div style=\"margin-top:16px;\">%s</div>

      <p style=\"margin:16px 0 0;color:#6b7280;font-size:12px;\">
        Si no esperabas este mensaje, podes ignorarlo.
      </p>
    </div>

    <div style=\"padding:18px 24px;font-size:12px;color:#6b7280;border-top:1px solid #f3f3f3;background:#fafafa;\">
      Coincidir
    </div>
  </div>
</body>
</html>
""").formatted(
                esc(now),
                esc(serviceLabel),
                escapedDetails,
                linkHtml
        );
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String safeTruncate(String v, int max) {
        if (v == null) return null;
        if (v.length() <= max) return v;
        return v.substring(0, max);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
