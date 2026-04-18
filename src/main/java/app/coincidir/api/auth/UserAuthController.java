package app.coincidir.api.auth;

import app.coincidir.api.auth.dto.LoginRequest;
import app.coincidir.api.auth.dto.LoginResponse;
import app.coincidir.api.auth.dto.UserMeDto;
import app.coincidir.api.auth.dto.ChangePasswordRequest;
import app.coincidir.api.auth.dto.ForgotPasswordRequest;
import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.UserAccountRepository;
import app.coincidir.api.security.JwtService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserAccountRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JavaMailSender mailSender;

    /**
     * Email FROM. Si no se define, usa un fallback.
     */
    @Value("${coincidir.mail-from:${coincidir.sales-email:no-reply@coincidir.com}}")
    private String mailFrom;

    /**
     * URL del portal (se muestra en el email).
     */
    @Value("${coincidir.portal-url:http://localhost:3000}")
    private String portalUrl;

    @Value("${coincidir.mail-bcc:}")
    private String mailBcc;

    // throttle simple en memoria: email -> epochMillis
    private final ConcurrentHashMap<String, Long> forgotThrottle = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        System.out.println(">>> USER LOGIN email=" + req.email());

        UserAccount user = userRepo.findByEmail(req.email())
                .orElseThrow(() -> {
                    System.out.println(">>> USER NOT FOUND");
                    return new RuntimeException("Credenciales incorrectas");
                });

        System.out.println(">>> USER FOUND id=" + user.getId());

        if (!encoder.matches(req.password(), user.getPassword())) {
            System.out.println(">>> PASSWORD MISMATCH");
            throw new RuntimeException("Credenciales incorrectas");
        }

        // Guardamos última conexión
        user.setLastLoginAt(Instant.now());
        userRepo.save(user);

        String token = jwt.generate(
                user.getEmail(),
                Map.of("uid", user.getId(), "role", user.getRole())
        );
        return new LoginResponse(token);
    }

    /**
     * Recuperación de contraseña. Valida que el email exista.
     * Genera una contraseña temporal y la envía por email.
     */
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest req) throws Exception {
        String email = req == null ? null : req.email();
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Ingresá un email válido.");
        }
        String normalized = email.trim().toLowerCase();

        // throttle: 1 envío cada 2 minutos por email
        long now = System.currentTimeMillis();
        Long last = forgotThrottle.get(normalized);
        if (last != null && (now - last) < 120_000L) {
            // 429 sería ideal, pero mantenemos 400 para simplificar manejo en front
            throw new BadRequestException("Ya se envió una contraseña hace unos instantes. Esperá 2 minutos y volvé a intentar.");
        }

        UserAccount user = userRepo.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new BadRequestException("El mail ingresado no es válido."));

        String raw = generateTempPassword(10);
        validateNewPassword("", raw); // reutiliza reglas mínimas
        user.setPassword(encoder.encode(raw));
        userRepo.save(user);

        sendRecoveryEmail(normalized, raw);
        forgotThrottle.put(normalized, now);

        return Map.of(
                "ok", true,
                "message", "Si el email está registrado, te enviamos una contraseña temporal para volver a ingresar."
        );
    }


    @GetMapping("/me")
    public UserMeDto me(Principal principal) {
        // principal.getName() = email del usuario autenticado
        UserAccount user = userRepo.findByEmail(principal.getName())
                .orElseThrow();
        return new UserMeDto(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getFirstName(),
                user.getLastName(),
                user.getLastLoginAt()
        );
    }

    private void sendRecoveryEmail(String toEmail, String rawPassword) throws Exception {
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
        helper.setText(renderRecoveryEmailHTML(toEmail, rawPassword), true);

        mailSender.send(msg);
    }

    private String renderRecoveryEmailHTML(String toEmail, String rawPassword) {
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
      <div style=\"margin:0;font-size:20px;font-weight:800;letter-spacing:0.2px;\">Recuperación de contraseña</div>
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">%s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Generamos una contraseña temporal para que puedas volver a ingresar:</p>

      <div style=\"border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">
        <div style=\"display:flex;gap:0;border-bottom:1px solid #f3f3f3;\">
          <div style=\"width:140px;background:#fafafa;padding:12px 14px;font-size:12px;color:#6b7280;\">Email</div>
          <div style=\"flex:1;padding:12px 14px;font-weight:700;color:#111827;\">%s</div>
        </div>
        <div style=\"display:flex;gap:0;\">
          <div style=\"width:140px;background:#fafafa;padding:12px 14px;font-size:12px;color:#6b7280;\">Contrasena</div>
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
""").formatted(esc(now), esc(toEmail), esc(rawPassword), loginLine);
    }

    private String generateTempPassword(int len) {
        // mezcla letras y números (evita caracteres raros para que sea fácil de tipear)
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
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

    /**
     * Cambia la contraseña del usuario autenticado.
     *
     * Reglas básicas:
     * - mínimo 8 caracteres
     * - debe incluir al menos 1 letra y 1 número
     * - no permite valores triviales (ej: 1234, password)
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest req, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BadRequestException("Sesión inválida. Volvé a iniciar sesión.");
        }

        String currentPw = req == null ? null : req.currentPassword();
        String newPw = req == null ? null : req.newPassword();

        if (currentPw == null || currentPw.isBlank()) {
            throw new BadRequestException("Ingresá tu contraseña actual.");
        }
        if (newPw == null || newPw.isBlank()) {
            throw new BadRequestException("Ingresá una nueva contraseña.");
        }

        UserAccount user = userRepo.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new BadRequestException("Usuario no encontrado."));

        if (!encoder.matches(currentPw, user.getPassword())) {
            throw new BadRequestException("La contraseña actual es incorrecta.");
        }

        String normalizedNew = newPw.trim();
        validateNewPassword(currentPw.trim(), normalizedNew);

        user.setPassword(encoder.encode(normalizedNew));
        userRepo.save(user);

        return Map.of(
                "ok", true,
                "message", "Contraseña actualizada correctamente."
        );
    }

    private static void validateNewPassword(String currentPw, String newPw) {
        if (newPw.length() < 8) {
            throw new BadRequestException("La nueva contraseña debe tener al menos 8 caracteres.");
        }
        boolean hasLetter = newPw.chars().anyMatch(Character::isLetter);
        boolean hasDigit = newPw.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BadRequestException("La nueva contraseña debe incluir al menos 1 letra y 1 número.");
        }
        String low = newPw.toLowerCase();
        if (low.contains("1234") || low.equals("password") || low.equals("contraseña") || low.equals("contrasena")) {
            throw new BadRequestException("Elegí una contraseña menos predecible (evitá 1234, password, etc.).");
        }
        if (currentPw != null && !currentPw.isBlank() && newPw.equals(currentPw)) {
            throw new BadRequestException("La nueva contraseña debe ser distinta a la actual.");
        }
    }
}
