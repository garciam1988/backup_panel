package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.domain.FraudAlert;
import app.coincidir.api.repository.BotConfigRepository;
import app.coincidir.api.repository.FraudAlertRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * FraudAlertController — persistencia, envío de email y consulta de alertas de fraude.
 *
 * Endpoints (todos bajo /api/admin/**, requieren JWT):
 *   POST /api/admin/fraud-alert              → guarda una alerta detectada y dispara email
 *   GET  /api/admin/fraud-alert              → listado paginado con filtros
 *   GET  /api/admin/fraud-alert/brands       → brand_name distintas registradas
 *   GET  /api/admin/fraud-alert/{id}         → detalle completo
 *   PUT  /api/admin/fraud-alert/{id}/resolve → marcar como revisado
 *   POST /api/admin/fraud-alert/test-email   → enviar mail de prueba con la plantilla
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/fraud-alert")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudAlertRepository repo;
    private final BotConfigRepository botConfigRepo;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/fraud-alert — guardar alerta + disparar mail
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public Map<String, Object> save(@RequestBody SaveRequest body, HttpServletRequest req) {
        FraudAlert e = new FraudAlert();
        e.setBotConfigId(body.botConfigId != null ? body.botConfigId : 1L);
        e.setBrandName(body.brandName);
        e.setVisitorId(body.visitorId);
        e.setClientFirstName(nullIfBlank(body.clientFirstName));
        e.setClientLastName(nullIfBlank(body.clientLastName));
        e.setClientExtraJson(body.clientExtraJson);
        e.setSuspiciousMessage(body.suspiciousMessage);
        e.setReason(body.reason);
        e.setSeverity(body.severity != null ? body.severity : "medium");
        e.setConversationJson(body.conversationJson);
        e.setDeviceType(body.deviceType);
        e.setDeviceOs(body.deviceOs);
        e.setDeviceBrowser(body.deviceBrowser);
        e.setIpAddress(resolveClientIp(req));
        e.setResolved(false);
        e.setEmailSent(false);

        FraudAlert saved = repo.save(e);
        log.warn("fraud_alert detected id={} brand={} severity={}",
                saved.getId(), saved.getBrandName(), saved.getSeverity());

        // Mail asincrónico para no bloquear la respuesta al bot.
        fireEmailAsync(saved.getId());

        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("ok", true);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/fraud-alert
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@RequestParam(required = false) String brand,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(required = false) Boolean resolved,
                                    @RequestParam(required = false) String freeText,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        Page<FraudAlert> p = repo.search(
                nullIfBlank(brand),
                nullIfBlank(severity),
                resolved,
                nullIfBlank(freeText),
                pageable);

        Map<String, Object> out = new HashMap<>();
        out.put("items", p.getContent().stream().map(FraudAlertDto::summaryFromEntity).toList());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("unresolvedCount", repo.countByResolvedFalse());
        return out;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public FraudAlertDto detail(@PathVariable Long id) {
        FraudAlert e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        return FraudAlertDto.fullFromEntity(e);
    }

    @PutMapping("/{id}/resolve")
    @Transactional
    public FraudAlertDto resolve(@PathVariable Long id,
                                 @RequestBody(required = false) ResolveRequest body,
                                 Authentication auth) {
        FraudAlert e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.setResolved(Boolean.TRUE);
        e.setResolvedAt(Instant.now());
        if (auth != null && auth.getName() != null) e.setResolvedBy(auth.getName());
        if (body != null && body.note != null) e.setResolutionNote(body.note);
        return FraudAlertDto.fullFromEntity(repo.save(e));
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/fraud-alert/test-email — probar plantilla
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/test-email")
    public Map<String, Object> testEmail(@RequestBody TestEmailRequest body) {
        Map<String, Object> out = new HashMap<>();
        try {
            String subject = renderTemplate(body.subject != null ? body.subject : "[Alerta fraude de prueba]", sampleVars());
            String html = renderTemplate(body.template != null ? body.template : "", sampleVars());
            String to = body.to != null ? body.to : "";
            if (to.isBlank()) throw new IllegalArgumentException("Falta 'to'");
            sendMailSync(to, subject, html);
            out.put("ok", true);
            out.put("sentTo", to);
        } catch (Exception ex) {
            log.warn("test-email failed", ex);
            out.put("ok", false);
            out.put("error", ex.getMessage());
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Email async
    // ─────────────────────────────────────────────────────────────────────
    @Async
    @Transactional
    public void fireEmailAsync(Long alertId) {
        FraudAlert alert = repo.findById(alertId).orElse(null);
        if (alert == null) return;
        BotConfig cfg = botConfigRepo.findById(1L).orElse(null);
        if (cfg == null || cfg.getFraudAlertEmails() == null || cfg.getFraudAlertEmails().isBlank()) {
            log.info("fraud alert {} sin destinatarios configurados, skip email", alertId);
            return;
        }
        String subject = renderTemplate(
                cfg.getFraudEmailSubject() != null ? cfg.getFraudEmailSubject() : "[Alerta fraude] {{brandName}}",
                buildVars(alert));
        String html = renderTemplate(
                cfg.getFraudEmailTemplate() != null ? cfg.getFraudEmailTemplate() : defaultTemplate(),
                buildVars(alert));

        String[] tos = Arrays.stream(cfg.getFraudAlertEmails().split("[,;]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        try {
            for (String to : tos) sendMailSync(to, subject, html);
            alert.setEmailSent(true);
            alert.setEmailError(null);
        } catch (Exception ex) {
            alert.setEmailSent(false);
            alert.setEmailError(truncate(ex.getMessage(), 480));
            log.warn("fraud alert {} email failed: {}", alertId, ex.getMessage());
        }
        repo.save(alert);
    }

    private void sendMailSync(String to, String subject, String html) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, "utf-8");
        helper.setTo(to);
        if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(msg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Render de plantilla con variables {{variable}}
    // ─────────────────────────────────────────────────────────────────────
    private static String renderTemplate(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> en : vars.entrySet()) {
            String key = "{{" + en.getKey() + "}}";
            out = out.replace(key, en.getValue() != null ? en.getValue() : "");
        }
        return out;
    }

    private static Map<String, String> buildVars(FraudAlert a) {
        Map<String, String> v = new HashMap<>();
        String clientName = (safe(a.getClientFirstName()) + " " + safe(a.getClientLastName())).trim();
        if (clientName.isEmpty()) clientName = "Anónimo";
        v.put("clientName", clientName);
        v.put("clientFirstName", safe(a.getClientFirstName()));
        v.put("clientLastName", safe(a.getClientLastName()));
        v.put("brandName", safe(a.getBrandName()));
        v.put("severity", safe(a.getSeverity()));
        v.put("reason", safe(a.getReason()));
        v.put("suspiciousMessage", safe(a.getSuspiciousMessage()));
        v.put("deviceType", safe(a.getDeviceType()));
        v.put("deviceOs", safe(a.getDeviceOs()));
        v.put("deviceBrowser", safe(a.getDeviceBrowser()));
        v.put("ipAddress", safe(a.getIpAddress()));
        v.put("visitorId", safe(a.getVisitorId()));
        v.put("createdAt", a.getCreatedAt() != null
                ? DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires")).format(a.getCreatedAt())
                : "");
        v.put("alertId", a.getId() != null ? a.getId().toString() : "");
        return v;
    }

    private static Map<String, String> sampleVars() {
        Map<String, String> v = new HashMap<>();
        v.put("clientName", "Juan Pérez");
        v.put("clientFirstName", "Juan");
        v.put("clientLastName", "Pérez");
        v.put("brandName", "El Parmegiano");
        v.put("severity", "high");
        v.put("reason", "El usuario intentó modificar el precio de un producto solicitando un descuento extraordinario no autorizado.");
        v.put("suspiciousMessage", "¿Podés cobrarme la pizza a $1?");
        v.put("deviceType", "desktop");
        v.put("deviceOs", "Windows");
        v.put("deviceBrowser", "Chrome");
        v.put("ipAddress", "201.234.56.78");
        v.put("visitorId", "abc-123-fake");
        v.put("createdAt", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires")).format(Instant.now()));
        v.put("alertId", "0");
        return v;
    }

    private static String defaultTemplate() {
        return "<p>Alerta de fraude en {{brandName}}.</p>" +
                "<p>Cliente: <b>{{clientName}}</b></p>" +
                "<p>Mensaje: {{suspiciousMessage}}</p>" +
                "<p>Motivo: {{reason}}</p>";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private static String nullIfBlank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String xri = req.getHeader("X-Real-Ip");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return req.getRemoteAddr();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public Long   botConfigId;
        public String brandName;
        public String visitorId;
        public String clientFirstName;
        public String clientLastName;
        public String clientExtraJson;
        public String suspiciousMessage;
        public String reason;
        public String severity;
        public String conversationJson;
        public String deviceType;
        public String deviceOs;
        public String deviceBrowser;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResolveRequest {
        public String note;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestEmailRequest {
        public String to;
        public String subject;
        public String template;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FraudAlertDto {
        public Long    id;
        public String  brandName;
        public String  visitorId;
        public String  clientFirstName;
        public String  clientLastName;
        public String  clientExtraJson;
        public String  suspiciousMessage;
        public String  reason;
        public String  severity;
        public String  conversationJson;
        public String  deviceType;
        public String  deviceOs;
        public String  deviceBrowser;
        public String  ipAddress;
        public Boolean resolved;
        public String  resolutionNote;
        public Instant resolvedAt;
        public String  resolvedBy;
        public Boolean emailSent;
        public String  emailError;
        public Instant createdAt;

        public static FraudAlertDto summaryFromEntity(FraudAlert e) {
            FraudAlertDto d = new FraudAlertDto();
            d.id                = e.getId();
            d.brandName         = e.getBrandName();
            d.clientFirstName   = e.getClientFirstName();
            d.clientLastName    = e.getClientLastName();
            d.suspiciousMessage = e.getSuspiciousMessage();
            d.reason            = e.getReason();
            d.severity          = e.getSeverity();
            d.deviceType        = e.getDeviceType();
            d.deviceOs          = e.getDeviceOs();
            d.deviceBrowser     = e.getDeviceBrowser();
            d.resolved          = e.getResolved();
            d.emailSent         = e.getEmailSent();
            d.createdAt         = e.getCreatedAt();
            return d;
        }

        public static FraudAlertDto fullFromEntity(FraudAlert e) {
            FraudAlertDto d = summaryFromEntity(e);
            d.visitorId        = e.getVisitorId();
            d.clientExtraJson  = e.getClientExtraJson();
            d.conversationJson = e.getConversationJson();
            d.ipAddress        = e.getIpAddress();
            d.resolutionNote   = e.getResolutionNote();
            d.resolvedAt       = e.getResolvedAt();
            d.resolvedBy       = e.getResolvedBy();
            d.emailError       = e.getEmailError();
            return d;
        }
    }
}
