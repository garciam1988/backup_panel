package app.coincidir.api.service;

import app.coincidir.api.domain.RequestStatus;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.TravelRequestAirService;
import app.coincidir.api.domain.MemberEmision;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.expense.ExpenseStatus;
import app.coincidir.api.domain.expense.ExpenseType;
import app.coincidir.api.domain.payment.InstallmentStatus;
import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.PaymentOneTimeMethod;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.repository.MemberEmisionRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelRequestAirServiceRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.UserAccountRepository;
import app.coincidir.api.web.admin.dto.CreateTravelRequestAirPaymentRequest;
import app.coincidir.api.web.dto.AirServiceDto;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TravelRequestService {

    private static final String TEMP_GROUP_DEST = "__TEMP_PAYMENTS__";
    private static final String TEMP_GROUP_WHEN = "TEMP";

    private final TravelRequestRepository repository;
    private final GroupServiceMenuItemRepository menuItemRepo;
    private final GroupAirServiceRepository groupAirRepo;
    private final TravelRequestAirServiceRepository requestAirRepo;
    private final MemberEmisionRepository memberEmisionRepo;
    private final MemberPaymentPlanRepository memberPaymentPlanRepo;
    private final MemberPaymentRecordRepository memberPaymentRecordRepo;
    private final ExpenseRepository expenseRepo;
    private final TravelGroupRepository groupRepo;
    private final JavaMailSender mailSender;

    private final UserAccountRepository userRepo;
    private final PasswordEncoder encoder;

    /**
     * Reutiliza la config existente. Si no se define coincidir.mail-from,
     * usa coincidir.sales-email y si no existe usa un fallback.
     */
    @Value("${coincidir.mail-from:${coincidir.sales-email:no-reply@coincidir.com}}")
    private String mailFrom;

    /**
     * Opcional. Se muestra en el mail.
     */
    @Value("${coincidir.portal-url:http://localhost:3000}")
    private String portalUrl;

    /**
     * Copia oculta para registrar envíos en una casilla (opcional).
     */
    @Value("${coincidir.mail-bcc:}")
    private String mailBcc;

    @Transactional
    public TravelRequest saveAndNotify(TravelRequest req) throws Exception {
        return saveInternal(req, true);
    }

    @Transactional
    public TravelRequest saveWithoutNotify(TravelRequest req) throws Exception {
        return saveInternal(req, false);
    }

    private TravelRequest saveInternal(TravelRequest req, boolean notifyAccessEmail) throws Exception {
        // Status por defecto
        if (req.getStatus() == null) {
            req.setStatus(RequestStatus.NEW);
        }

        // Normalizar email (evita duplicados por mayusculas/minusculas/espacios)
        if (req.getEmail() != null) {
            req.setEmail(req.getEmail().trim().toLowerCase());
        }

        // defaults + validaciones para viajeros
        Integer total  = Optional.ofNullable(req.getTravelersTotal()).orElse(1);
        Integer adults = Optional.ofNullable(req.getTravelersAdults()).orElse(1);
        Integer minors = Optional.ofNullable(req.getTravelersMinors()).orElse(0);

        if (adults < 0) {
            throw new IllegalArgumentException("El número de adultos no puede ser negativo.");
        }
        if (total < 1) {
            throw new IllegalArgumentException("El total de viajeros debe ser >= 1.");
        }
        if (!Integer.valueOf(adults + minors).equals(total)) {
            throw new IllegalArgumentException("Adultos + Menores debe igualar el Total.");
        }

        req.setTravelersTotal(total);
        req.setTravelersAdults(adults);
        req.setTravelersMinors(minors);

        TravelRequest saved = repository.save(req);

        // Crear usuario (si no existe). En carga manual hoy dejamos deshabilitado el envío de email,
        // pero mantenemos la creación/aseguramiento de la cuenta para reutilizarla más adelante.
        String email = saved.getEmail();
        if (email != null && !email.isBlank()) {
            AccessEmailResult access = ensureUserForAccess(email);
            if (notifyAccessEmail && access != null) {
                if (access.rawPassword() != null && !access.rawPassword().isBlank()) {
                    sendAccessEmail(email, access.rawPassword());
                } else {
                    sendExistingAccountEmail(email);
                }
            }
        }

        return saved;
    }

    /**
     * Asegura que exista el usuario para el email indicado.
     *
     * Importante:
     * - Si el usuario NO existe, se crea y se devuelve una contraseña en claro para enviar por email.
     * - Si el usuario YA existe, NO se modifica su password (no se puede recuperar el password actual).
     */
    private AccessEmailResult ensureUserForAccess(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase();
        if (normalized == null || normalized.isBlank()) return null;

        UserAccount ua = userRepo.findByEmail(normalized).orElse(null);
        if (ua == null) {
            String raw = generateSimplePassword(10);
            ua = UserAccount.builder()
                    .email(normalized)
                    .password(encoder.encode(raw))
                    .role("USER")
                    .build();

            try {
                userRepo.save(ua);
                return new AccessEmailResult(true, raw);
            } catch (DataIntegrityViolationException e) {
                // Carrera por email duplicado: reintenta leyendo (sin resetear password)
                ua = userRepo.findByEmail(normalized).orElse(null);
                if (ua == null) return null;
            }
        }

        // Si ya existía, NO pisamos password.
        return new AccessEmailResult(false, null);
    }

    private void sendAccessEmail(String toEmail, String rawPassword) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, "utf-8");

        helper.setTo(toEmail);
        if (mailFrom != null && !mailFrom.isBlank()) {
            helper.setFrom(mailFrom);
        }
        if (mailBcc != null && !mailBcc.isBlank()) {
            helper.setBcc(mailBcc);
        }
        helper.setSubject("Coincidir - Tus datos de acceso");
        helper.setText(renderAccessEmailHTML(toEmail, rawPassword), true);

        mailSender.send(msg);
    }

    private void sendExistingAccountEmail(String toEmail) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, "utf-8");

        helper.setTo(toEmail);
        if (mailFrom != null && !mailFrom.isBlank()) {
            helper.setFrom(mailFrom);
        }
        if (mailBcc != null && !mailBcc.isBlank()) {
            helper.setBcc(mailBcc);
        }
        helper.setSubject("Coincidir - Tu cuenta ya existe");
        helper.setText(renderExistingAccountEmailHTML(toEmail), true);

        mailSender.send(msg);
    }

    private String renderAccessEmailHTML(String toEmail, String rawPassword) {
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
      <div style=\"margin:0;font-size:20px;font-weight:800;letter-spacing:0.2px;\">Bienvenido a Coincidir</div>
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">Acceso creado el %s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Ya podes ingresar con estas credenciales:</p>

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
        Si no solicitaste esto, podes ignorar este email.
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
                esc(rawPassword),
                loginLine
        );
    }

    private String renderExistingAccountEmailHTML(String toEmail) {
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
      <div style=\"font-size:12px;opacity:.9;margin-top:4px;\">%s</div>
    </div>

    <div style=\"padding:20px 24px;color:#374151;\">
      <p style=\"margin:0 0 12px;\">Detectamos que tu cuenta ya existe.</p>

      <div style=\"border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">
        <div style=\"display:flex;gap:0;\">
          <div style=\"width:140px;background:#fafafa;padding:12px 14px;font-size:12px;color:#6b7280;\">Email</div>
          <div style=\"flex:1;padding:12px 14px;font-weight:700;color:#111827;\">%s</div>
        </div>
      </div>

      <p style=\"margin:12px 0 0;\">
        Por seguridad, <b>no generamos una nueva contraseña</b> ni modificamos la que ya tenés.
        Ingresá con tu contraseña actual.
      </p>

      %s

      <p style=\"margin:14px 0 0;color:#6b7280;font-size:12px;\">
        Si no recordás tu contraseña, respondé este email y te ayudamos a recuperarla.
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
                loginLine
        );
    }

    private record AccessEmailResult(boolean createdUser, String rawPassword) {}

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

    public record ReceiptDownload(byte[] bytes, String contentType, String fileName) {}

    @Transactional
    public void uploadDepositReceipt(Long requestId, MultipartFile file) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }

        TravelRequest req = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));


// Comprobante obligatorio
if (file == null || file.isEmpty()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El comprobante es obligatorio");
}

        try {
            if (file != null && !file.isEmpty()) {
                byte[] bytes = file.getBytes();
                if (bytes != null && bytes.length > 0) {
                    req.setDepositReceiptBlob(bytes);

                    String ct = file.getContentType();
                    if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                    req.setDepositReceiptContentType(ct);

                    String fn = file.getOriginalFilename();
                    req.setDepositReceiptFileName(fn);

                    repository.save(req);
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    /**
     * Materializa el pago de un pasajero cargado manualmente (SIN grupo) para que impacte
     * inmediatamente en conciliación (member_payment_plan / member_payment_installment / member_payment_record).
     */
    @Transactional
    public void materializeManualPassengerDepositPayment(Long requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }

        TravelRequest req = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));

        // Importante: ANTES de crear la operación el pasajero no tiene grupo.
        // En ese caso, el pago debe quedar SIN group_id (NULL) y aun así ser visible en conciliación.
        final Long currentGroupId = (req.getGroup() != null ? req.getGroup().getId() : null);
        final Long effectiveGroupId = currentGroupId;

        // Si ya existe un plan para el grupo efectivo, no duplicar.
        if (memberPaymentPlanRepo.findByGroupIdAndMemberId(effectiveGroupId, requestId).isPresent()) {
            return;
        }

        // Si ya existe un plan ungrouped, no duplicar.
        try {
            if (memberPaymentPlanRepo.findUngroupedWithInstallments(requestId).isPresent()) {
                return;
            }
        } catch (Exception ignored) {
        }

        // Requiere datos mínimos de pago.
        if (req.getDepositAmount() == null || req.getDepositAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }
        if (req.getDepositDate() == null) {
            return;
        }

        String pm = req.getDepositPaymentMethod();
        PaymentPlanType planType = PaymentPlanType.ONE_TIME;
        PaymentOneTimeMethod method = null;

        if (pm != null && pm.contains(":")) {
            String[] parts = pm.split(":", 2);
            try {
                if (parts.length > 0 && parts[0] != null && !parts[0].isBlank()) {
                    planType = PaymentPlanType.valueOf(parts[0].trim());
                }
            } catch (Exception ignored) {
            }
            try {
                if (parts.length > 1 && parts[1] != null && !parts[1].isBlank()) {
                    method = PaymentOneTimeMethod.valueOf(parts[1].trim());
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                if (pm != null && !pm.isBlank()) {
                    method = PaymentOneTimeMethod.valueOf(pm.trim());
                }
            } catch (Exception ignored) {
            }
        }

        String notes = req.getDepositNotes();
        com.fasterxml.jackson.databind.JsonNode meta = null;
        if (notes != null && notes.trim().startsWith("{")) {
            try {
                meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(notes);
            } catch (Exception ignored) {
            }
        }

        // Preferir meta planType/method si existen.
        if (meta != null) {
            try {
                if (meta.hasNonNull("planType")) {
                    planType = PaymentPlanType.valueOf(meta.get("planType").asText().trim());
                }
            } catch (Exception ignored) {
            }
            if (method == null) {
                try {
                    if (meta.hasNonNull("method")) {
                        method = PaymentOneTimeMethod.valueOf(meta.get("method").asText().trim());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (method == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Método de pago inválido");
        }

        String receiptLast4 = null;
        if (meta != null && meta.hasNonNull("receiptLast4")) {
            receiptLast4 = meta.get("receiptLast4").asText();
        }
        if (receiptLast4 == null || receiptLast4.isBlank()) {
            // Extraer últimos 4 dígitos desde texto libre (si existiera)
            if (notes != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(notes);
                while (m.find()) {
                    receiptLast4 = m.group(1);
                }
            }
        }
        if (receiptLast4 == null || receiptLast4.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Últimos 4 dígitos del comprobante inválidos");
        }

        java.time.LocalDate paymentDate = req.getDepositDate();
        java.math.BigDecimal totalAmount = req.getDepositAmount();

        // Regla: las fechas de las cuotas no pueden ser mayor a la fecha inicio de viaje.
        java.time.LocalDate travelStartLimit = req.getTravelStartDate();
        if (travelStartLimit == null) {
            travelStartLimit = req.getTravelDateStart();
        }
        if (travelStartLimit != null && paymentDate != null && paymentDate.isAfter(travelStartLimit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las fechas de las cuotas no pueden ser mayor a la fecha inicio de viaje.");
        }

        MemberPaymentPlan plan = new MemberPaymentPlan();
        plan.setGroupId(effectiveGroupId);
        plan.setMemberId(requestId);
        plan.setPlanType(planType);
        plan.setOneTimeMethod(method);
        plan.setTotalAmount(totalAmount);
        plan.setCurrency("ARS");
        plan.setReceiptLast4(receiptLast4);
        plan.setNotes(notes);

        java.math.BigDecimal firstAmount = totalAmount;

        if (planType == PaymentPlanType.OWN_FINANCING) {
            java.util.List<com.fasterxml.jackson.databind.JsonNode> instNodes = new java.util.ArrayList<>();
            if (meta != null && meta.has("installments") && meta.get("installments").isArray()) {
                meta.get("installments").forEach(instNodes::add);
            }

            if (instNodes.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El plan de financiación no tiene cuotas");
            }

            for (com.fasterxml.jackson.databind.JsonNode n : instNodes) {
                Integer num = null;
                try {
                    if (n.hasNonNull("installmentNumber")) num = n.get("installmentNumber").asInt();
                } catch (Exception ignored) {
                }
                if (num == null || num < 1) continue;

                java.math.BigDecimal amt = null;
                try {
                    if (n.hasNonNull("amount")) {
                        String raw = n.get("amount").asText();
                        if (raw != null) raw = raw.replace(",", ".").trim();
                        if (raw != null && !raw.isBlank()) amt = new java.math.BigDecimal(raw);
                    }
                } catch (Exception ignored) {
                }

                java.time.LocalDate due = null;
                try {
                    if (n.hasNonNull("dueDate")) {
                        String ds = n.get("dueDate").asText();
                        if (ds != null && !ds.isBlank()) due = java.time.LocalDate.parse(ds);
                    }
                } catch (Exception ignored) {
                }

                if (due == null) {
                    due = paymentDate.plusMonths(Math.max(0, num - 1));
                }

                if (travelStartLimit != null && due != null && due.isAfter(travelStartLimit)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las fechas de las cuotas no pueden ser mayor a la fecha inicio de viaje.");
                }


                MemberPaymentInstallment inst = new MemberPaymentInstallment();
                inst.setPlan(plan);
                inst.setInstallmentNumber(num);
                inst.setAmount(amt);
                inst.setDueDate(due);

                if (num == 1) {
                    inst.setStatus(InstallmentStatus.PAID);
                    inst.setPaidDate(paymentDate);
                    if (amt != null) firstAmount = amt;
                } else {
                    inst.setStatus(InstallmentStatus.PLANNED);
                    inst.setPaidDate(null);
                }

                plan.getInstallments().add(inst);
            }

            // Si por alguna razón no quedó cargada la cuota 1, crearla.
            boolean has1 = plan.getInstallments().stream().anyMatch(i -> i.getInstallmentNumber() != null && i.getInstallmentNumber() == 1);
            if (!has1) {
                MemberPaymentInstallment inst1 = new MemberPaymentInstallment();
                inst1.setPlan(plan);
                inst1.setInstallmentNumber(1);
                inst1.setAmount(firstAmount);
                inst1.setDueDate(paymentDate);
                inst1.setStatus(InstallmentStatus.PAID);
                inst1.setPaidDate(paymentDate);
                plan.getInstallments().add(0, inst1);
            }
        } else {
            MemberPaymentInstallment inst1 = new MemberPaymentInstallment();
            inst1.setPlan(plan);
            inst1.setInstallmentNumber(1);
            inst1.setAmount(totalAmount);
            inst1.setDueDate(paymentDate);
            inst1.setStatus(InstallmentStatus.PAID);
            inst1.setPaidDate(paymentDate);
            plan.getInstallments().add(inst1);
            firstAmount = totalAmount;
        }

        MemberPaymentPlan savedPlan = memberPaymentPlanRepo.save(plan);

        // Registrar pago (installment 1) si no existe.
        if (!memberPaymentRecordRepo.existsByPlanIdAndInstallmentNumber(savedPlan.getId(), 1)) {
            MemberPaymentRecord rec = new MemberPaymentRecord();
            rec.setPlan(savedPlan);
            rec.setGroupId(effectiveGroupId);
            rec.setMemberId(requestId);
            rec.setInstallmentNumber(1);
            rec.setAmount(firstAmount);
            rec.setCurrency("ARS");
            rec.setPaymentDate(paymentDate);
            rec.setReceiptLast4(receiptLast4);

            // Copiar comprobante desde travel_request (deposit_receipt_*)
            if (req.getDepositReceiptBlob() != null && req.getDepositReceiptBlob().length > 0) {
                rec.setReceiptBlob(req.getDepositReceiptBlob());
                rec.setReceiptContentType(req.getDepositReceiptContentType());
                rec.setReceiptFileName(req.getDepositReceiptFileName());
            }

            memberPaymentRecordRepo.save(rec);
        }

        // Importante: en carga manual de pasajero NO se registra en expenses,
        // para evitar duplicados en conciliación.
    }

    @Transactional
    public ReceiptDownload downloadDepositReceipt(Long requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }

        TravelRequest req = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));

        byte[] bytes = req.getDepositReceiptBlob();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }

        String ct = req.getDepositReceiptContentType();
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";

        String fn = req.getDepositReceiptFileName();
        if (fn == null || fn.isBlank()) fn = "comprobante";

        return new ReceiptDownload(bytes, ct, fn);
    }

    // ============================================================
    // AEREOS (precarga por pasajero)
    // ============================================================

    @Transactional
    public AirServiceDto getPassengerAirService(Long requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }
        TravelRequest tr = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));
        Long currentGroupId = (tr.getGroup() != null) ? tr.getGroup().getId() : null;

        AirServiceDto dto = requestAirRepo.findByRequest_Id(requestId)
                .map(this::toDto)
                .orElse(null);

        // Fallback: si no existe travel_request_air_service, intentar obtener la plantilla desde depositNotes (multi-aéreos)
        // para que la UI pueda preseleccionar datos del servicio al elegir "Fecha de viaje (en curso)".
        if (dto == null) {
            try {
                java.util.List<AirServiceDto> fromNotes = readAirServicesFromDepositNotes(tr.getDepositNotes());
                if (fromNotes != null && !fromNotes.isEmpty()) {
                    AirServiceDto first = fromNotes.get(0);
                    if (first != null) {
                        first.setId(null);
                        first.setGroupId(currentGroupId);
                        dto = first;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Si todavía no existe info de aéreos, devolver un DTO mínimo con la cotización precargada desde member_emision.
        if (dto == null) {
            java.util.Optional<MemberEmision> em = memberEmisionRepo.findTopByRequestIdOrderByCreatedAtDesc(requestId);
            if (em.isEmpty()) {
                // Último fallback: si el request ya está en un grupo con servicio AÉREOS, devolverlo aunque no haya member_emision
                if (currentGroupId != null) {
                    try {
                        java.util.Optional<GroupServiceMenuItem> miOpt =
                                menuItemRepo.findFirstByGroupIdAndService_CodeOrderByPositionAsc(currentGroupId, ServiceCode.AEREOS);
                        if (miOpt.isPresent()) {
                            java.util.Optional<GroupAirService> gasOpt = groupAirRepo.findByMenuItemId(miOpt.get().getId());
                            if (gasOpt.isPresent()) {
                                GroupAirService gas = gasOpt.get();
                                AirServiceDto g = new AirServiceDto();
                                g.setId(null);
                                g.setGroupId(currentGroupId);
                                if (gas.getTripType() != null) g.setTripType(gas.getTripType());
                                if (gas.getOrigin() != null) g.setOrigin(gas.getOrigin());
                                if (gas.getDestination() != null) g.setDestination(gas.getDestination());
                                if (gas.getAirline() != null) g.setAirline(gas.getAirline());
                                g.setDepartureDate(gas.getDepartureDate());
                                g.setDepartureTime(gas.getDepartureTime());
                                g.setDepartureArrivalTime(gas.getDepartureArrivalTime());
                                g.setReturnDate(gas.getReturnDate());
                                g.setReturnDepartureTime(gas.getReturnDepartureTime());
                                g.setReturnArrivalTime(gas.getReturnArrivalTime());
                                g.setBaggageAllowance(gas.getBaggageAllowance() != null ? gas.getBaggageAllowance() : "6_KG");
                                g.setReservationCode(gas.getReservationCode());
                                g.setNotes(gas.getNotes());
                                g.setTotalCost(gas.getTotalCost());
                                g.setTotalCostUpdatedAt(gas.getTotalCostUpdatedAt() != null ? gas.getTotalCostUpdatedAt().toString() : null);
                                // En grupos, la fuente de verdad de valor cotizado es total_cost
                                g.setQuotedValue(gas.getTotalCost());
                                g.setQuotedAt(gas.getTotalCostUpdatedAt() != null ? gas.getTotalCostUpdatedAt().toString() : null);
                                return g;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                return null;
            }
            dto = new AirServiceDto();
            dto.setId(null);
            dto.setGroupId(currentGroupId);
            dto.setQuotedValue(em.get().getQuotedValue());
            dto.setQuotedAt(em.get().getQuotedAt() != null ? em.get().getQuotedAt().toString() : null);
            // defaults por compatibilidad UI
            dto.setTripType("ONE_WAY");
            dto.setBaggageAllowance("6_KG");
            return dto;
        }

        dto.setGroupId(currentGroupId);

        // Si no hay cotización en el servicio del request, intentar completar desde member_emision.
        if (dto.getQuotedValue() == null) {
            try {
                java.util.Optional<MemberEmision> emOpt = memberEmisionRepo.findTopByRequestIdOrderByCreatedAtDesc(requestId);
                if (emOpt.isPresent()) {
                    MemberEmision em = emOpt.get();
                    dto.setQuotedValue(em.getQuotedValue());
                    dto.setQuotedAt(em.getQuotedAt() != null ? em.getQuotedAt().toString() : null);
                }
            } catch (Exception ignored) {
            }
        }

        // Si la solicitud ya está asociada a un grupo real y existe group_air_service,
        // completar datos faltantes desde el servicio del grupo (fuente de verdad para Operaciones).
        if (currentGroupId != null) {
            try {
                java.util.Optional<GroupServiceMenuItem> miOpt =
                        menuItemRepo.findFirstByGroupIdAndService_CodeOrderByPositionAsc(currentGroupId, ServiceCode.AEREOS);
                if (miOpt.isPresent()) {
                    java.util.Optional<GroupAirService> gasOpt = groupAirRepo.findByMenuItemId(miOpt.get().getId());
                    if (gasOpt.isPresent()) {
                        GroupAirService gas = gasOpt.get();

                        if (dto.getTripType() == null || dto.getTripType().isBlank()) dto.setTripType(gas.getTripType());
                        if (dto.getOrigin() == null || dto.getOrigin().isBlank()) dto.setOrigin(gas.getOrigin());
                        if (dto.getDestination() == null || dto.getDestination().isBlank()) dto.setDestination(gas.getDestination());
                        if (dto.getAirline() == null || dto.getAirline().isBlank()) dto.setAirline(gas.getAirline());

                        if (dto.getDepartureDate() == null) dto.setDepartureDate(gas.getDepartureDate());
                        if (dto.getDepartureTime() == null) dto.setDepartureTime(gas.getDepartureTime());
                        if (dto.getDepartureArrivalTime() == null) dto.setDepartureArrivalTime(gas.getDepartureArrivalTime());

                        if (dto.getReturnDate() == null) dto.setReturnDate(gas.getReturnDate());
                        if (dto.getReturnDepartureTime() == null) dto.setReturnDepartureTime(gas.getReturnDepartureTime());
                        if (dto.getReturnArrivalTime() == null) dto.setReturnArrivalTime(gas.getReturnArrivalTime());

                        if (dto.getBaggageAllowance() == null || dto.getBaggageAllowance().isBlank()) {
                            dto.setBaggageAllowance(gas.getBaggageAllowance() != null ? gas.getBaggageAllowance() : "6_KG");
                        }

                        if (dto.getReservationCode() == null || dto.getReservationCode().isBlank()) dto.setReservationCode(gas.getReservationCode());
                        if (dto.getNotes() == null || dto.getNotes().isBlank()) dto.setNotes(gas.getNotes());

                        if (dto.getTotalCost() == null) dto.setTotalCost(gas.getTotalCost());
                        if (dto.getTotalCostUpdatedAt() == null) {
                            dto.setTotalCostUpdatedAt(gas.getTotalCostUpdatedAt() != null ? gas.getTotalCostUpdatedAt().toString() : null);
                        }

                        // Mantener comportamiento: en grupos, mostrar total_cost como "Valor cotizado".
                        dto.setQuotedValue(gas.getTotalCost());
                        dto.setQuotedAt(gas.getTotalCostUpdatedAt() != null ? gas.getTotalCostUpdatedAt().toString() : null);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return dto;
    }

/**
     * Devuelve lista de aéreos precargados para el pasajero (max 3).
     *
     * - Si existe JSON "airServices" dentro de depositNotes, se devuelve esa lista.
     * - Si no existe, se mantiene compatibilidad devolviendo el único aéreo (travel_request_air_service).
     */
    @Transactional
    public java.util.List<AirServiceDto> getPassengerAirServices(Long requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }

        TravelRequest tr = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));

        java.util.List<AirServiceDto> fromNotes = readAirServicesFromDepositNotes(tr.getDepositNotes());
        if (fromNotes != null && !fromNotes.isEmpty()) {
            // Enriquecemos el primer tab con datos extra del servicio persistido (si existe)
            try {
                AirServiceDto base = getPassengerAirService(requestId);
                if (base != null) {
                    AirServiceDto first = fromNotes.get(0);
                    if (first != null) {
                        first.setId(base.getId());
                        first.setGroupId(base.getGroupId());
                        if (first.getQuotedValue() == null) first.setQuotedValue(base.getQuotedValue());
                        if (first.getQuotedAt() == null) first.setQuotedAt(base.getQuotedAt());
                        if (first.getTotalCost() == null) first.setTotalCost(base.getTotalCost());
                        if (first.getTotalCostUpdatedAt() == null) first.setTotalCostUpdatedAt(base.getTotalCostUpdatedAt());
                        if (first.getReservationCode() == null) first.setReservationCode(base.getReservationCode());
                        if (first.getTripType() == null) first.setTripType(base.getTripType());
                        if (first.getBaggageAllowance() == null) first.setBaggageAllowance(base.getBaggageAllowance());
                    }
                }
            } catch (Exception ignored) {
            }

            return fromNotes.size() > 3 ? fromNotes.subList(0, 3) : fromNotes;
        }

        AirServiceDto single = getPassengerAirService(requestId);
        if (single == null) return java.util.Collections.emptyList();
        return java.util.List.of(single);
    }

    private java.util.List<AirServiceDto> readAirServicesFromDepositNotes(String depositNotes) {
        if (depositNotes == null || depositNotes.isBlank()) return java.util.Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(depositNotes);
            com.fasterxml.jackson.databind.JsonNode arr = root == null ? null : root.get("airServices");
            if (arr == null || !arr.isArray()) return java.util.Collections.emptyList();

            java.util.List<AirServiceDto> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n == null || !n.isObject()) continue;
                AirServiceDto dto = new AirServiceDto();
                dto.setTripType(text(n, "tripType"));
                dto.setOrigin(text(n, "origin"));
                dto.setDestination(text(n, "destination"));
                dto.setAirline(text(n, "airline"));
                dto.setDepartureDate(parseDate(text(n, "departureDate")));
                dto.setDepartureTime(parseTime(text(n, "departureTime")));
                dto.setDepartureArrivalTime(parseTime(text(n, "departureArrivalTime")));
                dto.setReturnDate(parseDate(text(n, "returnDate")));
                dto.setReturnDepartureTime(parseTime(text(n, "returnDepartureTime")));
                dto.setReturnArrivalTime(parseTime(text(n, "returnArrivalTime")));
                dto.setBaggageAllowance(text(n, "baggageAllowance"));

                boolean any = (dto.getOrigin() != null && !dto.getOrigin().isBlank())
                        || (dto.getDestination() != null && !dto.getDestination().isBlank())
                        || (dto.getAirline() != null && !dto.getAirline().isBlank())
                        || dto.getDepartureDate() != null
                        || dto.getDepartureTime() != null;
                if (!any) continue;

                if (dto.getTripType() == null || dto.getTripType().isBlank()) {
                    dto.setTripType(dto.getReturnDate() != null ? "ROUND_TRIP" : "ONE_WAY");
                } else {
                    String tt = dto.getTripType().trim().toUpperCase();
                    if (!tt.equals("ONE_WAY") && !tt.equals("ROUND_TRIP")) {
                        tt = dto.getReturnDate() != null ? "ROUND_TRIP" : "ONE_WAY";
                    }
                    dto.setTripType(tt);
                }
                if (dto.getBaggageAllowance() == null || dto.getBaggageAllowance().isBlank()) {
                    dto.setBaggageAllowance("6_KG");
                }

                out.add(dto);
                if (out.size() >= 3) break;
            }
            return out;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String key) {
        if (node == null || key == null) return null;
        com.fasterxml.jackson.databind.JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String t = v.asText(null);
        if (t == null) return null;
        t = t.trim();
        return t.isBlank() ? null : t;
    }

    private static java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static java.time.LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalTime.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public AirServiceDto upsertPassengerAirService(Long requestId, AirServiceDto body) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }
        TravelRequest req = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));

        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body requerido");
        }

        TravelRequestAirService entity = requestAirRepo.findByRequest_Id(requestId)
                .orElseGet(() -> {
                    TravelRequestAirService x = new TravelRequestAirService();
                    x.setRequest(req);
                    return x;
                });

        // Permitir "actualización liviana" (ej: registrar pago) sin exigir completar todo el formulario.
        // Si solo vienen totalCost / cotización / código de reserva (y no vienen campos de vuelo), no validamos origen/destino/aerolínea/fechas.
        boolean lightUpdate =
                (body.getOrigin() == null || body.getOrigin().isBlank())
                        && (body.getDestination() == null || body.getDestination().isBlank())
                        && (body.getAirline() == null || body.getAirline().isBlank())
                        && body.getDepartureDate() == null
                        && body.getDepartureTime() == null
                        && body.getDepartureArrivalTime() == null
                        && body.getReturnDate() == null
                        && body.getReturnDepartureTime() == null
                        && body.getReturnArrivalTime() == null
                        && (body.getTripType() == null || body.getTripType().isBlank())
                        && (body.getBaggageAllowance() == null || body.getBaggageAllowance().isBlank());

        // Cotización / costo (opcionales)
        applyQuoteIfChanged(body.getQuotedValue(), entity.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        applyTotalCostIfChanged(body.getTotalCost(), entity.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);

        // Código de reserva: si el cliente lo envía, se actualiza (permite setearlo aunque falten datos del vuelo)
        if (body.getReservationCode() != null) {
            String rc = body.getReservationCode().trim();
            entity.setReservationCode(rc.isBlank() ? null : rc);
        }

        // Fecha vencimiento documento: si el cliente lo envía, se actualiza
        if (body.getDocumentExpirationDate() != null) {
            entity.setDocumentExpirationDate(body.getDocumentExpirationDate());
        }

        // Si es solo actualización liviana, no tocar el resto.
        if (lightUpdate) {
            if (entity.getTripType() == null || entity.getTripType().isBlank()) {
                entity.setTripType("ONE_WAY");
            }
            if (entity.getBaggageAllowance() == null || entity.getBaggageAllowance().isBlank()) {
                entity.setBaggageAllowance("6_KG");
            }
            TravelRequestAirService saved = requestAirRepo.save(entity);
            return toDto(saved);
        }

        // Actualización completa (o parcial con campos de vuelo): merge + validar por resultado final.
        String origin = (body.getOrigin() == null || body.getOrigin().isBlank()) ? entity.getOrigin() : body.getOrigin().trim();
        String destination = (body.getDestination() == null || body.getDestination().isBlank()) ? entity.getDestination() : body.getDestination().trim();
        String airline = (body.getAirline() == null || body.getAirline().isBlank()) ? entity.getAirline() : body.getAirline().trim();

        java.time.LocalDate depDate = body.getDepartureDate() != null ? body.getDepartureDate() : entity.getDepartureDate();
        java.time.LocalTime depTime = body.getDepartureTime() != null ? body.getDepartureTime() : entity.getDepartureTime();
        java.time.LocalTime depArr = body.getDepartureArrivalTime() != null ? body.getDepartureArrivalTime() : entity.getDepartureArrivalTime();

        java.time.LocalDate retDate = body.getReturnDate() != null ? body.getReturnDate() : entity.getReturnDate();
        java.time.LocalTime retTime = body.getReturnDepartureTime() != null ? body.getReturnDepartureTime() : entity.getReturnDepartureTime();
        java.time.LocalTime retArr = body.getReturnArrivalTime() != null ? body.getReturnArrivalTime() : entity.getReturnArrivalTime();

        String tripType = (body.getTripType() == null || body.getTripType().isBlank()) ? entity.getTripType() : body.getTripType();
        if (tripType == null || tripType.isBlank()) {
            tripType = (retDate != null) ? "ROUND_TRIP" : "ONE_WAY";
        }
        tripType = tripType.toUpperCase();
        if (!tripType.equals("ONE_WAY") && !tripType.equals("ROUND_TRIP")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tripType inválido. Use ONE_WAY o ROUND_TRIP");
        }

        if (origin == null || origin.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El origen es obligatorio");
        }
        if (destination == null || destination.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El destino es obligatorio");
        }
        if (airline == null || airline.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La aerolínea es obligatoria");
        }
        if (depDate == null || depTime == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha y hora de salida (ida) son obligatorias");
        }
        if (tripType.equals("ROUND_TRIP")) {
            if (retDate == null || retTime == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "En ida y vuelta, fecha y hora de salida (regreso) son obligatorias");
            }
        }

        // Fecha vencimiento documento: obligatoria en actualización completa
        java.time.LocalDate docExpDate = body.getDocumentExpirationDate() != null
                ? body.getDocumentExpirationDate()
                : entity.getDocumentExpirationDate();
        if (docExpDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de vencimiento del documento es obligatoria");
        }

        entity.setTripType(tripType);
        entity.setOrigin(origin);
        entity.setDestination(destination);
        entity.setAirline(airline);

        entity.setDepartureDate(depDate);
        entity.setDepartureTime(depTime);
        entity.setDepartureArrivalTime(depArr);

        if (tripType.equals("ONE_WAY")) {
            entity.setReturnDate(null);
            entity.setReturnDepartureTime(null);
            entity.setReturnArrivalTime(null);
        } else {
            entity.setReturnDate(retDate);
            entity.setReturnDepartureTime(retTime);
            entity.setReturnArrivalTime(retArr);
        }

        if (body.getBaggageAllowance() != null && !body.getBaggageAllowance().isBlank()) {
            entity.setBaggageAllowance(body.getBaggageAllowance().trim());
        } else if (entity.getBaggageAllowance() == null || entity.getBaggageAllowance().isBlank()) {
            entity.setBaggageAllowance("6_KG");
        }

        entity.setDocumentExpirationDate(docExpDate);

        TravelRequestAirService saved = requestAirRepo.save(entity);
        return toDto(saved);
    }

    
    private Long getOrCreateTempGroupId() {
        // Evitar duplicados históricos: si existen varios __TEMP_PAYMENTS__, usar el de menor id.
        try {
            java.util.List<TravelGroup> existing = groupRepo.findAllByDestinationOrdered(TEMP_GROUP_DEST);
            if (existing != null && !existing.isEmpty()) {
                TravelGroup g = existing.get(0);
                if (g.getWhenLabel() == null || g.getWhenLabel().isBlank()) g.setWhenLabel(TEMP_GROUP_WHEN);
                if (g.getTravelDateLabel() == null || g.getTravelDateLabel().isBlank()) g.setTravelDateLabel(TEMP_GROUP_WHEN);
                groupRepo.save(g);
                return g.getId();
            }
        } catch (Exception ignored) {
        }

        return groupRepo.save(TravelGroup.builder()
                        .status(GroupStatus.OPEN)
                        .destination(TEMP_GROUP_DEST)
                        .whenLabel(TEMP_GROUP_WHEN)
                        .travelDateLabel(TEMP_GROUP_WHEN)
                        .companionPreference("ANY")
                        .smokeFree(false)
                        .sizeTarget(1)
                        .createdAt(Instant.now())
                        .autoSearchEnabled(false)
                        .operationConfirmed(false)
                        .build())
                .getId();
    }

@Transactional
    public Long createPassengerAirPayment(Long requestId, CreateTravelRequestAirPaymentRequest body) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }
        TravelRequest tr = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: " + requestId));
        Long currentGroupId = (tr.getGroup() == null ? null : tr.getGroup().getId());
        Long effectiveGroupId = (currentGroupId != null ? currentGroupId : getOrCreateTempGroupId());

        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body requerido");
        }
        if (body.amount() == null || body.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El importe es obligatorio");
        }
        if (body.paymentDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de pago es obligatoria");
        }
        String last4 = (body.receiptLast4() == null) ? null : body.receiptLast4().trim();
        if (last4 == null || !last4.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Últimos 4 dígitos del comprobante inválidos");
        }
        if (body.receipt() == null || body.receipt().base64() == null || body.receipt().base64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El comprobante es obligatorio");
        }

        // Validación: para registrar el pago, el Total costo debe estar cargado.
        // (No exigir el resto de los datos del formulario; esos son obligatorios recién para emitir.)
        java.math.BigDecimal totalCost = requestAirRepo.findByRequest_Id(requestId)
                .map(TravelRequestAirService::getTotalCost)
                .orElse(null);
        if (totalCost == null || totalCost.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total costo es obligatorio");
        }
        if (body.amount().compareTo(totalCost) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El importe debe ser igual al costo actual");
        }

        // Evita duplicados (UI solo permite 1 carga por pasajero en esta pantalla)
        boolean hasExisting = !expenseRepo
                .findAllByGroupIdAndNotesContaining(effectiveGroupId, "pasajeroId=" + requestId)
                .isEmpty();
        if (hasExisting) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un pago cargado para este pasajero");
        }

        String currency = (body.currency() == null || body.currency().isBlank()) ? "ARS" : body.currency().trim();
        PaymentOneTimeMethod method;
        try {
            method = parseOneTimeMethod(body.paymentMethod());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Método de pago inválido");
        }

        byte[] receiptBytes;
        try {
            receiptBytes = java.util.Base64.getDecoder().decode(body.receipt().base64());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comprobante inválido (base64)");
        }

        // Conciliación (Check Panel): registrar egreso/gasto para que aparezca en la grilla
        Expense exp = new Expense();
        exp.setGroupId(effectiveGroupId);

// Si el request ya está asociado a un grupo real, enlazar el expense al menú de Aéreos del grupo
if (currentGroupId != null) {
    try {
        menuItemRepo.findFirstByGroupIdAndService_CodeOrderByPositionAsc(currentGroupId, ServiceCode.AEREOS)
                .ifPresent(mi -> exp.setMenuItemId(mi.getId()));
    } catch (Exception ignored) {}
}

        exp.setDate(body.paymentDate());
        exp.setType(ExpenseType.PROVEEDOR);
        exp.setCategory("OPERACIONES");
        String paxName = (tr.getName() == null || tr.getName().isBlank()) ? ("Pasajero " + requestId) : tr.getName().trim();
        exp.setConcept("Total Aereo Parcial - " + paxName);
        exp.setPaymentMethod(method == null ? null : method.name());
        exp.setStatus(ExpenseStatus.PAGADO);
        exp.setAmount(body.amount());
        exp.setCurrency(currency);
        exp.setReceiptNumber(null);
        exp.setReceiptLast4(last4);
        exp.setProvider(null);
        String notes = "Pago aéreos temporal - pasajeroId=" + requestId + " - groupId=" + effectiveGroupId;
        try {
            String rc = body.reservationCode();
            if (rc != null) {
                rc = rc.trim();
                if (!rc.isBlank()) {
                    notes = notes + " - reserva=" + rc;
                }
            }
        } catch (Exception ignored) {
        }
        exp.setNotes(notes);
        exp.setReceiptBlob(receiptBytes);
        exp.setReceiptContentType(body.receipt().contentType());
        exp.setReceiptFileName(body.receipt().fileName());
        Expense saved = expenseRepo.save(exp);

        // Auto-actualizar estado del servicio AEREOS: PENDIENTE -> EMITIDO cuando totalCost queda totalmente pagado
        try {
            if (exp.getMenuItemId() != null) {
                menuItemRepo.findById(exp.getMenuItemId()).ifPresent(mi -> {
                    try {
                        java.math.BigDecimal groupTotalCost = groupAirRepo.findByMenuItemId(mi.getId())
                                .map(app.coincidir.api.domain.GroupAirService::getTotalCost)
                                .orElse(null);

                        OperationStatusCode desired = OperationStatusCode.PENDIENTE;
                        if (groupTotalCost != null && groupTotalCost.compareTo(java.math.BigDecimal.ZERO) > 0) {
                            java.util.LinkedHashMap<Long, Expense> uniq = new java.util.LinkedHashMap<>();
                            for (Expense e : expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(effectiveGroupId, mi.getId())) {
                                if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
                            }
                            for (Expense e : expenseRepo.findAllByGroupIdAndNotesContaining(effectiveGroupId, "Pago aéreos temporal")) {
                                if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
                            }
                            java.math.BigDecimal paid = uniq.values().stream()
                                    .filter(java.util.Objects::nonNull)
                                    .map(Expense::getAmount)
                                    .filter(java.util.Objects::nonNull)
                                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                            java.math.BigDecimal remaining = groupTotalCost.subtract(paid != null ? paid : java.math.BigDecimal.ZERO);
                            if (remaining.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                                desired = OperationStatusCode.EMITIDO;
                            }
                        }

                        if (mi.getOperationStatus() == null || mi.getOperationStatus() != desired) {
                            mi.setOperationStatus(desired);
                            mi.setOperationStatusUpdatedAt(java.time.Instant.now());
                            menuItemRepo.save(mi);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }

        return saved.getId();
    }

    private void applyQuoteIfChanged(
            java.math.BigDecimal incomingValue,
            java.math.BigDecimal currentValue,
            java.util.function.Consumer<java.math.BigDecimal> setValue,
            java.util.function.Consumer<Instant> setAt
    ) {
        if (incomingValue == null) {
            if (currentValue != null) {
                setValue.accept(null);
                setAt.accept(null);
            }
            return;
        }
        // Siempre que se guarde un valor cotizado, la fecha de cotización queda en "hoy"
        setValue.accept(incomingValue);
        setAt.accept(Instant.now());
    }

    private void applyTotalCostIfChanged(
            java.math.BigDecimal incomingValue,
            java.math.BigDecimal currentValue,
            java.util.function.Consumer<java.math.BigDecimal> setValue,
            java.util.function.Consumer<java.time.Instant> setAt
    ) {
        if (incomingValue == null) {
            if (currentValue != null) {
                setValue.accept(null);
                setAt.accept(null);
            }
            return;
        }
        if (currentValue == null || incomingValue.compareTo(currentValue) != 0) {
            setValue.accept(incomingValue);
            setAt.accept(java.time.Instant.now());
        }
    }

    private AirServiceDto toDto(TravelRequestAirService e) {
        AirServiceDto dto = new AirServiceDto();
        dto.setId(e.getId());
        dto.setGroupId(null);
        dto.setQuotedValue(e.getQuotedValue());
        dto.setQuotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null);

        dto.setTripType(e.getTripType());
        dto.setOrigin(e.getOrigin());
        dto.setDestination(e.getDestination());
        dto.setAirline(e.getAirline());

        dto.setDepartureDate(e.getDepartureDate());
        dto.setDepartureTime(e.getDepartureTime());
        dto.setDepartureArrivalTime(e.getDepartureArrivalTime());

        dto.setReturnDate(e.getReturnDate());
        dto.setReturnDepartureTime(e.getReturnDepartureTime());
        dto.setReturnArrivalTime(e.getReturnArrivalTime());

        dto.setBaggageAllowance(e.getBaggageAllowance());
        dto.setReservationCode(e.getReservationCode());
        dto.setDocumentExpirationDate(e.getDocumentExpirationDate());
        dto.setTotalCost(e.getTotalCost());
        dto.setTotalCostUpdatedAt(e.getTotalCostUpdatedAt() != null ? e.getTotalCostUpdatedAt().toString() : null);
        return dto;
    }


    private PaymentOneTimeMethod parseOneTimeMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentOneTimeMethod.TRANSFERENCIA;
        }
        String norm = raw.trim().toUpperCase();
        try {
            norm = java.text.Normalizer.normalize(norm, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
        } catch (Exception ignore) {
        }
        norm = norm.replaceAll("[\\s\\-]+", "_");
        // aliases
        if (norm.equals("CASH")) norm = "EFECTIVO";
        if (norm.equals("TARJETA") || norm.equals("CARD")) norm = "TARJETA_CREDITO";
        if (norm.equals("DEBITO")) norm = "TARJETA_DEBITO";
        if (norm.equals("CREDITO")) norm = "TARJETA_CREDITO";
        if (norm.equals("TRANSFERENCIA_BANCARIA")) norm = "TRANSFERENCIA";
        return PaymentOneTimeMethod.valueOf(norm);
    }

}