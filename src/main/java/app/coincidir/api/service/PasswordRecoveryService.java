package app.coincidir.api.service;

import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.UserAccountRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PasswordRecoveryService {

    private final UserAccountRepository userRepo;
    private final PasswordEncoder encoder;
    private final JavaMailSender mailSender;

    @Value("${coincidir.mail-from:${coincidir.sales-email:no-reply@coincidir.com}}")
    private String mailFrom;

    @Value("${coincidir.portal-url:http://localhost:3000}")
    private String portalUrl;

    @Value("${coincidir.mail-bcc:}")
    private String mailBcc;

    /**
     * Throttle simple en memoria para evitar spam de mails.
     * (No persiste entre reinicios; suficiente para entorno dev/testing.)
     */
    private static final Map<String, Instant> LAST_SENT = new ConcurrentHashMap<>();

    public enum RecoveryResult {
        SENT,
        TOO_SOON,
        INVALID_EMAIL,
        USER_NOT_FOUND,
        FAILED
    }

    /**
     * Envía una contraseña temporal y pisa el password anterior.
     *
     * Nota: este método devuelve estados diferenciados (incluye USER_NOT_FOUND)
     * para permitir flujos de UX que quieran informar al usuario.
     */
    public RecoveryResult sendTemporaryPassword(String email) {
        String normalized = normalize(email);
        if (normalized == null || normalized.isBlank()) return RecoveryResult.INVALID_EMAIL;
        if (!looksLikeEmail(normalized)) return RecoveryResult.INVALID_EMAIL;

        // throttle: 1 mail por email cada 2 minutos
        Instant now = Instant.now();
        Instant last = LAST_SENT.get(normalized);
        if (last != null && now.minusSeconds(120).isBefore(last)) {
            return RecoveryResult.TOO_SOON;
        }

        UserAccount user = userRepo.findByEmail(normalized).orElse(null);
        if (user == null) {
            return RecoveryResult.USER_NOT_FOUND;
        }

        String tempPassword = generateSimplePassword(10);
        user.setPassword(encoder.encode(tempPassword));
        userRepo.save(user);

        try {
            sendRecoveryEmail(normalized, tempPassword);
            LAST_SENT.put(normalized, now);
            return RecoveryResult.SENT;
        } catch (Exception e) {
            System.err.println("[PasswordRecovery] Error enviando email a " + normalized + ": " + e.getMessage());
            return RecoveryResult.FAILED;
        }
    }

    private static boolean looksLikeEmail(String email) {
        // validación simple (suficiente para UX)
        int at = email.indexOf('@');
        if (at <= 0) return false;
        int dot = email.lastIndexOf('.');
        return dot > at + 1 && dot < email.length() - 1;
    }

    private void sendRecoveryEmail(String toEmail, String tempPassword) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, "utf-8");

        helper.setTo(toEmail);
        if (mailFrom != null && !mailFrom.isBlank()) {
            helper.setFrom(mailFrom);
        }
        if (mailBcc != null && !mailBcc.isBlank()) {
            helper.setBcc(mailBcc);
        }
        helper.setSubject("Coincidir - Recuperación de contraseña");
        helper.setText(renderRecoveryEmailHTML(toEmail, tempPassword), true);

        mailSender.send(msg);
    }

    private String renderRecoveryEmailHTML(String toEmail, String tempPassword) {
        String loginUrl = portalUrl == null ? "" : portalUrl.trim();
        if (!loginUrl.isBlank() && !loginUrl.contains("/login")) {
            loginUrl = loginUrl.replaceAll("/+$", "") + "/login";
        }

        String now = LocalDateTime.now().toString();

        String loginLine = loginUrl.isBlank()
                ? ""
                : ("<p style=\"margin:12px 0 0;\">Link de acceso: <a href=\"" + esc(loginUrl) + "\" style=\"color:#ff4d00;font-weight:700;\">" + esc(loginUrl) + "</a></p>");

        return ("""
<!doctype html>
<html>
<head><meta charset=\"utf-8\"/></head>
<body style=\"font-family:Inter,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#f8f8f8;margin:0;padding:24px;color:#222;\">
  <div style=\"max-width:640px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb;\">
    <div style=\"background:linear-gradient(135deg,#ff7a1a,#ff4d00);padding:20px 24px;color:#fff;\">
      <div style=\"margin:0;font-size:20px;font-weight:800;letter-spacing:0.2px;\">Coincidir</div>
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">Recuperación solicitada el %s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Generamos una <b>contraseña temporal</b> para que puedas volver a ingresar:</p>

      <div style=\"border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">
        <div style=\"display:flex;gap:0;border-bottom:1px solid #f3f3f3;\">
          <div style=\"width:140px;background:#fafafa;padding:12px 14px;font-size:12px;color:#6b7280;\">Email</div>
          <div style=\"flex:1;padding:12px 14px;font-weight:700;color:#111827;\">%s</div>
        </div>
        <div style=\"display:flex;gap:0;\">
          <div style=\"width:140px;background:#fafafa;padding:12px 14px;font-size:12px;color:#6b7280;\">Contraseña temporal</div>
          <div style=\"flex:1;padding:12px 14px;font-weight:700;color:#111827;\">%s</div>
        </div>
      </div>

      %s

      <p style=\"margin:14px 0 0;color:#6b7280;font-size:12px;\">
        Si no solicitaste esto, podés ignorar este email.
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
                esc(toEmail),
                esc(tempPassword),
                loginLine
        );
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static final SecureRandom RNG = new SecureRandom();

    // Sin caracteres ambiguos (0/O, 1/I/l)
    private static final char[] PASS_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private static String generateSimplePassword(int length) {
        int len = Math.max(8, length);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(PASS_CHARS[RNG.nextInt(PASS_CHARS.length)]);
        }
        return sb.toString();
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#039;");
    }
}
