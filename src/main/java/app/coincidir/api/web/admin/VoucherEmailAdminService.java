package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.SendVoucherEmailRequest;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

@Service
@RequiredArgsConstructor
public class VoucherEmailAdminService {

    private final JavaMailSender mailSender;

    @Value("${coincidir.mail-from:YES Travel <info@yes-traveluy.com>}")
    private String mailFrom;

    public org.springframework.http.ResponseEntity<?> testSmtp(String to) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject("Test SMTP - Coincidir");
            helper.setText("<p>Test de conexión SMTP exitoso desde Railway.</p>", true);
            mailSender.send(message);
            System.out.println("[SMTP TEST] OK - mail enviado a " + to + " desde " + mailFrom);
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("ok", true, "to", to, "from", mailFrom));
        } catch (Exception ex) {
            String cause = ex.getCause() != null ? ex.getCause().getMessage() : "";
            String detail = ex.getMessage() + (cause.isEmpty() ? "" : " | Cause: " + cause);
            System.err.println("[SMTP TEST ERROR] " + detail);
            ex.printStackTrace();
            return org.springframework.http.ResponseEntity.status(502)
                    .body(java.util.Map.of("ok", false, "error", detail));
        }
    }

    public void sendVoucherEmail(SendVoucherEmailRequest body) {
        String to = trim(body == null ? null : body.to());
        if (to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email de destinatario requerido");
        }

        byte[] attachmentBytes = decodeAttachment(body == null ? null : body.attachmentBase64());
        String attachmentFileName = trim(body == null ? null : body.attachmentFileName());
        String attachmentContentType = trim(body == null ? null : body.attachmentContentType());
        String subject = body == null || body.subject() == null ? "" : body.subject();
        String html = body == null || body.html() == null ? "" : body.html();

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachmentBytes != null, "UTF-8");
            if (mailFrom != null && !mailFrom.isBlank()) {
                helper.setFrom(mailFrom);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            if (attachmentBytes != null) {
                helper.addAttachment(
                        attachmentFileName == null ? "voucher.pdf" : attachmentFileName,
                        new ByteArrayResource(attachmentBytes),
                        attachmentContentType == null ? "application/pdf" : attachmentContentType
                );
            }

            mailSender.send(message);
        } catch (Exception ex) {
            // Log completo para diagnóstico en Railway
            String cause = ex.getCause() != null ? ex.getCause().getMessage() : "";
            String detail = ex.getMessage() + (cause.isEmpty() ? "" : " | Cause: " + cause);
            System.err.println("[SMTP ERROR] " + detail);
            ex.printStackTrace();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo entregar el mail: " + detail
            );
        }
    }

    private static byte[] decodeAttachment(String attachmentBase64) {
        String normalized = trim(attachmentBase64);
        if (normalized == null) return null;
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjunto de voucher inválido");
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
