package app.coincidir.api.web.admin;

import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupDestinationTransferService;
import app.coincidir.api.domain.GroupFerryService;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.GroupTransferService;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.repository.GroupAccommodationServiceRepository;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.repository.GroupDestinationTransferServiceRepository;
import app.coincidir.api.repository.GroupFerryServiceRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.repository.GroupTransferServiceRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.scheduling.annotation.Async;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OperationConfirmedEmailService {

    private final JavaMailSender mailSender;
    private final TravelRequestRepository requestRepo;
    private final GroupServiceMenuItemRepository menuRepo;
    private final MemberPaymentPlanRepository paymentPlanRepo;
    private final GroupFerryServiceRepository ferryRepo;
    private final GroupAirServiceRepository airRepo;
    private final GroupAccommodationServiceRepository accomRepo;
    private final GroupTransferServiceRepository transferRepo;
    private final GroupDestinationTransferServiceRepository destTransferRepo;

    @Value("${coincidir.mail-from:YES Travel <info@yes-traveluy.com>}")
    private String mailFrom;

    @Value("${coincidir.mail-bcc:info@yes-traveluy.com}")
    private String mailBcc;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d 'de' MMMM, yyyy", new Locale("es", "ES"));

    @Async
    @Transactional
    public void sendConfirmationEmail(TravelGroup group) {
        try {
            Long titularId = readTitularMemberId(group);
            if (titularId == null) {
                System.out.println("[CONFIRM EMAIL] Grupo " + group.getId() + " sin titular configurado, email omitido.");
                return;
            }

            Optional<TravelRequest> titularOpt = requestRepo.findById(titularId);
            if (titularOpt.isEmpty()) {
                System.out.println("[CONFIRM EMAIL] Titular id=" + titularId + " no encontrado, email omitido.");
                return;
            }

            TravelRequest titular = titularOpt.get();
            String toEmail = titular.getEmail();
            if (toEmail == null || toEmail.isBlank()) {
                System.out.println("[CONFIRM EMAIL] Titular sin email, email omitido.");
                return;
            }

            List<TravelRequest> allMembers = requestRepo.findByGroupIdOrderByIdAsc(group.getId());
            List<GroupServiceMenuItem> services = menuRepo.findByGroupIdOrderByPositionAsc(group.getId());
            Optional<MemberPaymentPlan> plan = paymentPlanRepo.findByGroupIdAndMemberId(group.getId(), titularId);

            String html = buildHtml(group, titular, titularId, allMembers, services, plan.orElse(null));
            byte[] condicionesPdf = buildCondicionesPdf(group.getId());

            MimeMessage message = mailSender.createMimeMessage();

            boolean hasLogo = false;
            try {
                ClassPathResource logoResource = new ClassPathResource("static/yes-travel-logo.jpg");
                if (logoResource.exists()) {
                    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                    if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
                    helper.setTo(toEmail);
                    if (mailBcc != null && !mailBcc.isBlank()) helper.setBcc(mailBcc);
                    helper.setSubject("Confirmacion de operacion #" + group.getId() + " - YES Travel");
                    helper.setText(html, true);
                    helper.addInline("yes-travel-logo", logoResource, "image/jpeg");
                    helper.addAttachment(
                        "Condiciones-Operacion-" + group.getId() + ".pdf",
                        new ByteArrayResource(condicionesPdf),
                        "application/pdf"
                    );
                    hasLogo = true;
                    mailSender.send(message);
                }
            } catch (Exception ignored) { }

            if (!hasLogo) {
                MimeMessage msg2 = mailSender.createMimeMessage();
                MimeMessageHelper helper2 = new MimeMessageHelper(msg2, true, "UTF-8");
                if (mailFrom != null && !mailFrom.isBlank()) helper2.setFrom(mailFrom);
                helper2.setTo(toEmail);
                if (mailBcc != null && !mailBcc.isBlank()) helper2.setBcc(mailBcc);
                helper2.setSubject("Confirmacion de operacion #" + group.getId() + " - YES Travel");
                String htmlNoLogo = html.replace(
                    "src=\"cid:yes-travel-logo\"",
                    "src=\"\" style=\"display:none\""
                );
                helper2.setText(htmlNoLogo, true);
                helper2.addAttachment(
                    "Condiciones-Operacion-" + group.getId() + ".pdf",
                    new ByteArrayResource(condicionesPdf),
                    "application/pdf"
                );
                mailSender.send(msg2);
            }

            System.out.println("[CONFIRM EMAIL] OK -> " + toEmail + " (grupo #" + group.getId() + ")");
        } catch (Exception ex) {
            System.err.println("[CONFIRM EMAIL ERROR] grupo=" + group.getId() + " | " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTML Builder
    // ─────────────────────────────────────────────────────────────

    private String buildHtml(TravelGroup group, TravelRequest titular, Long titularId,
                              List<TravelRequest> allMembers, List<GroupServiceMenuItem> services,
                              MemberPaymentPlan plan) {

        String dest = (group.getDestination() != null && !group.getDestination().isBlank())
                ? group.getDestination().toUpperCase()
                : "—";

        // Fechas: misma lógica que GroupAdminService.resolveGroupTravelEndDate
        String fechaSalida = "A confirmar";
        String fechaRegreso = "A confirmar";

        LocalDate startDate = group.getTravelStartDate();
        if (startDate == null && allMembers != null) {
            startDate = allMembers.stream()
                    .filter(r -> r.getTravelStartDate() != null)
                    .map(TravelRequest::getTravelStartDate)
                    .findFirst().orElse(null);
        }
        if (startDate != null) {
            fechaSalida = startDate.format(DATE_FMT);
        } else if (group.getTravelDateLabel() != null && !group.getTravelDateLabel().isBlank()) {
            fechaSalida = group.getTravelDateLabel();
        }

        LocalDate endDate = group.getTravelEndDate();
        if (endDate == null && allMembers != null) {
            endDate = allMembers.stream()
                    .filter(r -> r.getTravelEndDate() != null)
                    .map(TravelRequest::getTravelEndDate)
                    .findFirst().orElse(null);
        }
        if (endDate != null) {
            fechaRegreso = endDate.format(DATE_FMT);
        }

        // Forma de pago
        String formaPago = "—";
        if (plan != null) {
            formaPago = plan.getPlanType() == PaymentPlanType.OWN_FINANCING
                    ? "Pago en cuotas / financiacion propia"
                    : "Pago en una cuota";
        }

        // ── Bloque pasajeros ──
        StringBuilder passengersHtml = new StringBuilder();
        boolean multiMember = allMembers != null && allMembers.size() > 1;

        if (!multiMember) {
            TravelRequest m = (allMembers != null && !allMembers.isEmpty()) ? allMembers.get(0) : titular;
            passengersHtml.append(singlePassengerBlock(m));
        } else {
            passengersHtml.append(
                "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;\">"
                + "<tr style=\"background:linear-gradient(135deg,#0f766e,#14b8a6);\">"
                + thCell("Pasajero") + thCell("DNI") + thCell("Email") + thCell("Rol")
                + "</tr>"
            );
            for (int i = 0; i < allMembers.size(); i++) {
                TravelRequest m = allMembers.get(i);
                boolean isTitular = m.getId() != null && m.getId().equals(titularId);
                String bg = (i % 2 == 0) ? "#f8fafc" : "#ffffff";
                passengersHtml.append(
                    "<tr style=\"background:" + bg + ";\">"
                    + tdCell("<span style=\"font-weight:" + (isTitular ? "700" : "500") + ";color:#0f172a;\">"
                        + escHtml(safe(m.getName(), "—")) + "</span>")
                    + tdCell(escHtml(safe(m.getDni(), "—")))
                    + tdCell("<span style=\"font-size:12px;\">" + escHtml(safe(m.getEmail(), "—")) + "</span>")
                    + "<td style=\"padding:9px 12px;text-align:center;border-bottom:1px solid #e2e8f0;\">"
                    + (isTitular
                        ? "<span style=\"background:#0f766e;color:#fff;border-radius:20px;padding:3px 10px;font-size:11px;font-weight:700;\">Titular</span>"
                        : "<span style=\"color:#94a3b8;font-size:12px;\">Pasajero</span>")
                    + "</td></tr>"
                );
            }
            passengersHtml.append("</table>");
        }

        // ── Bloque servicios ──
        StringBuilder servicesHtml = new StringBuilder();
        if (services == null || services.isEmpty()) {
            servicesHtml.append("<p style=\"margin:0;font-size:13px;color:#94a3b8;\">Sin servicios registrados.</p>");
        } else {
            servicesHtml.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">");
            for (GroupServiceMenuItem svc : services) {
                String code = (svc.getService() != null && svc.getService().getCode() != null)
                        ? svc.getService().getCode().name() : "";
                String icon = serviceIcon(code);
                String detail = buildServiceDetail(code, svc.getId());
                servicesHtml.append(
                    "<tr><td style=\"padding:10px 0;vertical-align:top;border-bottom:1px solid #f1f5f9;\">"
                    + "<span style=\"font-size:18px;margin-right:10px;vertical-align:middle;\">" + icon + "</span>"
                    + "<span style=\"color:#0f172a;font-size:14px;font-weight:600;vertical-align:middle;\">" + escHtml(svc.getDisplayName()) + "</span>"
                    + (detail.isBlank() ? "" : "<div style=\"margin-top:4px;margin-left:28px;font-size:12px;color:#64748b;\">" + detail + "</div>")
                    + "</td></tr>"
                );
            }
            servicesHtml.append("</table>");
        }

        // ─────────────────────── HTML completo ───────────────────────
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f1f5f9;"
                + "font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;\">"

                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f1f5f9;padding:30px 0;\">"
                + "<tr><td align=\"center\">"
                + "<table width=\"640\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"max-width:640px;width:100%;border-radius:16px;overflow:hidden;"
                + "box-shadow:0 4px 28px rgba(15,118,110,0.15);\">"

                // ══ HEADER teal ══
                + "<tr><td style=\"background:linear-gradient(135deg,#0f766e 0%,#14b8a6 60%,#059669 100%);padding:28px 36px;\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td valign=\"middle\">"
                + "<h1 style=\"margin:0;color:#fff;font-size:22px;font-weight:800;letter-spacing:-0.4px;line-height:1.25;\">&#x2713; Tu operacion esta confirmada!</h1>"
                + "<p style=\"margin:6px 0 0;color:rgba(255,255,255,0.78);font-size:12px;\">YES Travel &middot; Confirmacion oficial de viaje</p>"
                + "</td>"
                // Logo CID inline
                + "<td valign=\"middle\" align=\"right\" style=\"padding-left:16px;width:1%;white-space:nowrap;\">"
                + "<div style=\"background:#fff;border-radius:10px;padding:5px 9px;display:inline-block;box-shadow:0 4px 14px rgba(0,0,0,0.18);\">"
                + "<img src=\"cid:yes-travel-logo\" alt=\"YES Travel\" height=\"44\" style=\"display:block;height:44px;width:auto;\"/>"
                + "</div>"
                + "</td>"
                + "</tr></table>"
                + "</td></tr>"

                // ══ BANDA código de operación — fondo teal claro ══
                + "<tr><td style=\"background:#CCFBF1;padding:10px 36px;\">"
                + "<span style=\"font-size:14px;color:#0f766e;font-weight:800;\">ID Operacion: " + group.getId() + "</span>"
                + "</td></tr>"

                // ══ CUERPO blanco ══
                + "<tr><td style=\"background:#fff;padding:28px 36px;\">"

                // ── Destino + Fechas ──
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:22px;\"><tr>"
                + "<td width=\"48%\" valign=\"top\" style=\"border-right:2px solid #CCFBF1;padding-right:18px;\">"
                + "<p style=\"margin:0 0 6px;font-size:10px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#0f766e;\">Destino</p>"
                + "<p style=\"margin:0;font-size:20px;font-weight:800;color:#0f172a;letter-spacing:-0.3px;line-height:1.2;\">" + escHtml(dest) + "</p>"
                + "</td>"
                + "<td width=\"52%\" valign=\"top\" style=\"padding-left:18px;\">"
                + "<p style=\"margin:0 0 10px;font-size:10px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#0f766e;\">Fechas del Viaje</p>"
                + dateRow("&#x1F6EB;", "Salida", fechaSalida)
                + dateRow("&#x1F6EC;", "Regreso", fechaRegreso)
                + "</td>"
                + "</tr></table>"
                + separator()

                // ── Pasajeros ──
                + "<p style=\"margin:0 0 14px;font-size:10px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#0f766e;\">Pasajeros</p>"
                + passengersHtml
                + separator()

                // ── Servicios ──
                + "<p style=\"margin:0 0 8px;font-size:10px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#0f766e;\">Servicios Contratados</p>"
                + servicesHtml
                + separator()

                // ── Forma de pago ──
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td><p style=\"margin:0;font-size:10px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#0f766e;\">Forma de Pago</p></td>"
                + "<td align=\"right\"><p style=\"margin:0;font-size:13px;color:#374151;\">&#x1F4B3; " + escHtml(formaPago) + "</p></td>"
                + "</tr></table>"
                + separator()

                // ── Nota condiciones ──
                + "<p style=\"margin:0;font-size:13px;color:#6b7280;\">&#x1F4CE; Las condiciones generales de la operacion se incluyen como PDF adjunto a este correo.</p>"

                + "</td></tr>"

                // ══ BLOQUE ASISTENTE IA ══
                + "<tr><td style=\"background:#eff6ff;padding:24px 36px;border-top:2px solid #bfdbfe;\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td valign=\"top\" style=\"padding-right:16px;\">"
                + "<p style=\"margin:0 0 6px;font-size:15px;font-weight:800;color:#1d4ed8;\">&#x1F916; Asistente IA disponible 24/7</p>"
                + "<p style=\"margin:0;font-size:13px;color:#374151;line-height:1.6;\">Tenes alguna duda sobre tu viaje? Nuestro asistente virtual con inteligencia artificial esta disponible <strong>las 24 horas, los 7 dias de la semana</strong>, listo para acompañarte y responder todo lo que necesites durante toda la experiencia.</p>"
                + "</td>"
                + "<td valign=\"middle\" align=\"right\" style=\"width:1%;white-space:nowrap;padding-left:16px;\">"
                + "<a href=\"https://asistente.yes-traveluy.com/\" target=\"_blank\""
                + "   style=\"display:inline-block;background:#2563eb;color:#fff;text-decoration:none;"
                + "   padding:11px 22px;border-radius:8px;font-size:13px;font-weight:700;"
                + "   box-shadow:0 2px 8px rgba(37,99,235,0.3);\">&#x1F4AC; Consultar ahora</a>"
                + "</td>"
                + "</tr></table>"
                + "</td></tr>"

                // ══ FOOTER ══
                + "<tr><td style=\"background:linear-gradient(135deg,#0f766e 0%,#14b8a6 100%);padding:18px 36px;text-align:center;\">"
                + "<p style=\"margin:0;color:rgba(255,255,255,0.8);font-size:12px;\">&copy; YES Travel &middot; Confirmacion oficial de su operacion.</p>"
                + "<p style=\"margin:4px 0 0;color:rgba(255,255,255,0.55);font-size:11px;\">Ante cualquier consulta, responda este correo o contactenos por WhatsApp.</p>"
                + "</td></tr>"

                + "</table></td></tr></table>"
                + "</body></html>";
    }

    private String singlePassengerBlock(TravelRequest m) {
        return "<p style=\"margin:0 0 9px;font-size:14px;color:#0f172a;\">"
                + "<span style=\"margin-right:8px;\">&#x1F464;</span>"
                + "<b>" + escHtml(safe(m.getName(), "—")) + "</b></p>"
                + "<p style=\"margin:0 0 9px;font-size:14px;color:#374151;\">"
                + "<span style=\"margin-right:8px;\">&#x1FAA6;</span>"
                + "<b>DNI:</b> " + escHtml(safe(m.getDni(), "—")) + "</p>"
                + (m.getEmail() != null && !m.getEmail().isBlank()
                    ? "<p style=\"margin:0;font-size:13px;color:#374151;\">"
                      + "<span style=\"margin-right:8px;\">&#x2709;</span>"
                      + escHtml(m.getEmail()) + "</p>"
                    : "");
    }

    private String dateRow(String icon, String label, String value) {
        return "<p style=\"margin:0 0 8px;font-size:13px;color:#374151;\">"
                + "<span style=\"margin-right:6px;\">" + icon + "</span>"
                + "<span style=\"color:#64748b;\">" + label + ":</span> "
                + "<b style=\"color:#0f172a;\">" + escHtml(value) + "</b>"
                + "</p>";
    }

    private String separator() {
        return "<div style=\"height:1px;background:#CCFBF1;margin:20px 0;\"></div>";
    }

    private String thCell(String text) {
        return "<th style=\"padding:8px 12px;text-align:left;font-size:11px;font-weight:700;"
                + "color:#fff;letter-spacing:0.06em;text-transform:uppercase;\">" + text + "</th>";
    }

    private String tdCell(String content) {
        return "<td style=\"padding:9px 12px;font-size:13px;color:#374151;"
                + "border-bottom:1px solid #e2e8f0;\">" + content + "</td>";
    }

    private String buildServiceDetail(String code, Long menuItemId) {
        if (menuItemId == null || code == null) return "";
        try {
            return switch (code.toUpperCase()) {
                case "FERRY" -> {
                    var opt = ferryRepo.findByMenuItemId(menuItemId);
                    if (opt.isEmpty()) yield "";
                    GroupFerryService f = opt.get();
                    String origin = safe(f.getOriginPort(), "");
                    String dest   = safe(f.getDestinationPort(), "");
                    String route  = (!origin.isBlank() && !dest.isBlank()) ? escHtml(origin) + " &#x2192; " + escHtml(dest) : "";
                    String bus    = "";
                    if (f.getBusOrigin() != null && !f.getBusOrigin().isBlank())
                        bus = " &nbsp;|&nbsp; Bus: " + escHtml(f.getBusOrigin())
                            + (f.getBusDestination() != null && !f.getBusDestination().isBlank()
                               ? " &#x2192; " + escHtml(f.getBusDestination()) : "");
                    yield route + bus;
                }
                case "AEREOS" -> {
                    var opt = airRepo.findByMenuItemId(menuItemId);
                    if (opt.isEmpty()) yield "";
                    GroupAirService a = opt.get();
                    String origin = safe(a.getOrigin(), "");
                    String dest   = safe(a.getDestination(), "");
                    yield (!origin.isBlank() && !dest.isBlank())
                        ? escHtml(origin) + " &#x2192; " + escHtml(dest) : "";
                }
                case "ALOJAMIENTOS" -> {
                    var opt = accomRepo.findByMenuItemId(menuItemId);
                    if (opt.isEmpty()) yield "";
                    GroupAccommodationService a = opt.get();
                    String city  = safe(a.getCity(), "");
                    String name  = safe(a.getThirdPartyName(), "");
                    if (!name.isBlank() && !city.isBlank()) yield escHtml(name) + " &mdash; " + escHtml(city);
                    if (!name.isBlank()) yield escHtml(name);
                    if (!city.isBlank()) yield escHtml(city);
                    yield "";
                }
                case "TRASLADOS_BA" -> {
                    var opt = transferRepo.findByMenuItemId(menuItemId);
                    if (opt.isEmpty()) yield "";
                    GroupTransferService t = opt.get();
                    String from = safe(t.getPickupPointName() != null ? t.getPickupPointName() : t.getPickupPlace(), "");
                    String to   = safe(t.getDestinationPointName() != null ? t.getDestinationPointName() : t.getDestinationPlace(), "");
                    yield (!from.isBlank() && !to.isBlank())
                        ? escHtml(from) + " &#x2192; " + escHtml(to) : "";
                }
                case "TRASLADOS_DESTINO" -> {
                    var opt = destTransferRepo.findByMenuItemId(menuItemId);
                    if (opt.isEmpty()) yield "";
                    GroupDestinationTransferService t = opt.get();
                    String from = safe(t.getPickupPointName() != null ? t.getPickupPointName() : t.getPickupPlace(), "");
                    String to   = safe(t.getDestinationPointName() != null ? t.getDestinationPointName() : t.getDestinationPlace(), "");
                    yield (!from.isBlank() && !to.isBlank())
                        ? escHtml(from) + " &#x2192; " + escHtml(to) : "";
                }
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private String serviceIcon(String code) {
        if (code == null) return "&#x1F6CE;";
        return switch (code.toUpperCase()) {
            case "FERRY"        -> "&#x26F4;";
            case "AEREOS"       -> "&#x2708;";
            case "ALOJAMIENTOS" -> "&#x1F3E8;";
            case "TRASLADOS_BA", "TRASLADOS_DESTINO", "TRASLADOS" -> "&#x1F697;";
            default             -> "&#x1F6CE;";
        };
    }

    // ─────────────────────────────────────────────────────────────
    // PDF Condiciones Generales
    // ─────────────────────────────────────────────────────────────

    private byte[] buildCondicionesPdf(Long groupId) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 50, 50, 60, 50);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Color teal      = new Color(15, 118, 110);
            Color tealLight = new Color(204, 251, 241);
            Color darkText  = new Color(15, 23, 42);
            Color grayText  = new Color(100, 116, 139);

            com.lowagie.text.Font titleFont   = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 18, com.lowagie.text.Font.BOLD,   Color.WHITE);
            com.lowagie.text.Font subFont     = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 10, com.lowagie.text.Font.NORMAL,  new Color(200, 200, 200));
            com.lowagie.text.Font sectionFont = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA,  9, com.lowagie.text.Font.BOLD,    teal);
            com.lowagie.text.Font headFont    = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 10, com.lowagie.text.Font.BOLD,    darkText);
            com.lowagie.text.Font bodyFont    = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA,  9, com.lowagie.text.Font.NORMAL,  grayText);
            com.lowagie.text.Font footerFont  = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA,  9, com.lowagie.text.Font.NORMAL,  Color.WHITE);

            // ── Header ──
            PdfPTable header = new PdfPTable(1);
            header.setWidthPercentage(100);
            PdfPCell hCell = new PdfPCell();
            hCell.setBackgroundColor(teal);
            hCell.setPadding(16);
            hCell.setBorder(Rectangle.NO_BORDER);
            hCell.addElement(new Paragraph("YES Travel", titleFont));
            hCell.addElement(new Paragraph("Condiciones Generales  ·  Operacion #" + groupId, subFont));
            header.addCell(hCell);
            doc.add(header);

            // Banda codigo
            PdfPTable banda = new PdfPTable(2);
            banda.setWidthPercentage(100);
            banda.setSpacingBefore(0);
            PdfPCell b1 = cell("Confirmacion de Operacion y Condiciones Generales", sectionFont, tealLight);
            b1.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell b2 = cell("#" + groupId, new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 10, com.lowagie.text.Font.BOLD, teal), tealLight);
            b2.setHorizontalAlignment(Element.ALIGN_RIGHT);
            banda.addCell(b1);
            banda.addCell(b2);
            doc.add(banda);

            doc.add(new Paragraph(" "));

            // ── Condiciones ──
            String[][] conds = condicionesData();
            for (String[] c : conds) {
                Paragraph title = new Paragraph(c[0], headFont);
                title.setSpacingBefore(8);
                title.setSpacingAfter(2);
                doc.add(title);
                Paragraph body = new Paragraph(c[1], bodyFont);
                body.setSpacingAfter(4);
                body.setLeading(13);
                doc.add(body);
            }

            // ── Footer ──
            doc.add(new Paragraph(" "));
            PdfPTable footer = new PdfPTable(1);
            footer.setWidthPercentage(100);
            PdfPCell fCell = new PdfPCell();
            fCell.setBackgroundColor(teal);
            fCell.setPadding(10);
            fCell.setBorder(Rectangle.NO_BORDER);
            fCell.addElement(new Paragraph(
                "© YES Travel  ·  Ante cualquier consulta, responda este correo o contactenos por WhatsApp.",
                footerFont
            ));
            footer.addCell(fCell);
            doc.add(footer);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[CONFIRM EMAIL] Error generando PDF condiciones: " + e.getMessage());
            return new byte[0];
        }
    }

    private PdfPCell cell(String text, com.lowagie.text.Font font, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setPadding(8);
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private String[][] condicionesData() {
        return new String[][] {
            { "1. Aceptacion de condiciones",
              "Las presentes condiciones se tendran por conocidas y aceptadas desde que ocurra cualquiera de las siguientes circunstancias: a) el pago de la seña y/o de cualquier suma a cuenta; b) la confirmacion expresa por correo electronico o WhatsApp; c) la solicitud de emision y/o reserva de cualquiera de los servicios; d) la utilizacion total o parcial de los servicios contratados; e) la falta de observacion fehaciente dentro de las 24 horas corridas de recibido este correo, siempre que ademas exista continuidad en el proceso de contratacion." },
            { "2. Vigencia de cotizacion",
              "Las tarifas informadas tienen vigencia unicamente hasta la fecha expresamente indicada en la cotizacion. Vencido dicho plazo, la tarifa y disponibilidad quedan sujetas a reconfirmacion." },
            { "3. Seña y congelamiento de tarifa",
              "El pago de la seña permite reservar la operacion bajo las condiciones informadas, sujeto a disponibilidad real y a los plazos de emision de cada prestador." },
            { "4. Pagos en cuotas / servicios emitidos",
              "Cuando el pasajero opte por pago parcial o cuotas, reconoce que la agencia podra avanzar con reservas y emisiones antes de que el precio total haya sido cancelado. En caso de cancelacion posterior, el pasajero debera abonar como minimo el valor de los servicios ya emitidos o reservados." },
            { "5. Servicios no reembolsables / penalidades",
              "Determinados servicios pueden ser no reembolsables o estar sujetos a penalidades segun cada prestador. Las penalidades derivadas de cancelaciones, cambios o no show seran a exclusivo cargo del pasajero." },
            { "6. Cancelaciones voluntarias",
              "Toda cancelacion podra generar cargos administrativos, penalidades de prestadores y perdida total o parcial de importes abonados." },
            { "7. Cambios de fecha, ruta, nombre o servicios",
              "Toda modificacion queda sujeta a disponibilidad, aceptacion del prestador y pago de diferencias tarifarias. La agencia no garantiza que los servicios admitan cambios una vez emitidos." },
            { "8. Cancelaciones o cambios operativos de terceros",
              "La agencia no resulta responsable por cancelaciones, demoras, huelgas, condiciones climaticas, disposiciones gubernamentales o fuerza mayor." },
            { "9. Servicios aereos",
              "Los servicios aereos se rigen por las condiciones de la aerolinea emisora. El no show puede provocar la perdida de tramos siguientes o del ticket completo." },
            { "10. Reintegros",
              "Todo reintegro queda sujeto a autorizacion y liquidacion del prestador, deduccion de penalidades e impuestos no reintegrables." },
            { "11. Datos del pasajero y documentacion",
              "Es responsabilidad del pasajero verificar que sus datos personales sean correctos y coincidan con su documentacion de viaje." },
            { "12. Presentacion y no show",
              "La falta de presentacion en horario podra implicar la perdida total del servicio sin derecho a reintegro." },
            { "13. Intermediacion",
              "YES TRAVEL actua como intermediaria y gestora comercial de los servicios turisticos contratados." },
            { "14. Jurisdiccion / comunicaciones",
              "Toda comunicacion por WhatsApp, email, vouchers o facturas vinculados a esta operacion forma parte de la contratacion. El pasajero constituye como validos los datos de contacto informados al momento de la reserva." },
        };
    }

    private String condicionesHtml() {
        return "<div style=\"font-size:12px;color:#6B7280;line-height:1.75;background:#f8fafc;"
                + "border-radius:8px;padding:16px 18px;border:1px solid #e2e8f0;\">"
                + cond("1. Aceptacion de condiciones",
                    "Las presentes condiciones se tendran por conocidas y aceptadas desde que ocurra cualquiera de las siguientes circunstancias: a) el pago de la seña y/o de cualquier suma a cuenta; b) la confirmacion expresa por correo electronico o WhatsApp; c) la solicitud de emision y/o reserva de cualquiera de los servicios; d) la utilizacion total o parcial de los servicios contratados; e) la falta de observacion fehaciente dentro de las 24 horas corridas de recibido este correo, siempre que ademas exista continuidad en el proceso de contratacion.")
                + cond("2. Vigencia de cotizacion",
                    "Las tarifas informadas tienen vigencia unicamente hasta la fecha expresamente indicada en la cotizacion. Vencido dicho plazo, la tarifa y disponibilidad quedan sujetas a reconfirmacion.")
                + cond("3. Seña y congelamiento de tarifa",
                    "El pago de la seña permite reservar la operacion bajo las condiciones informadas, sujeto a disponibilidad real y a los plazos de emision de cada prestador.")
                + cond("4. Pagos en cuotas / servicios emitidos",
                    "Cuando el pasajero opte por pago parcial o cuotas, reconoce que la agencia podra avanzar con reservas y emisiones antes de que el precio total haya sido cancelado. En caso de cancelacion posterior, el pasajero debera abonar como minimo el valor de los servicios ya emitidos o reservados.")
                + cond("5. Servicios no reembolsables / penalidades",
                    "Determinados servicios pueden ser no reembolsables o estar sujetos a penalidades segun cada prestador. Las penalidades derivadas de cancelaciones, cambios o no show seran a exclusivo cargo del pasajero.")
                + cond("6. Cancelaciones voluntarias",
                    "Toda cancelacion podra generar cargos administrativos, penalidades de prestadores y perdida total o parcial de importes abonados.")
                + cond("7. Cambios de fecha, ruta, nombre o servicios",
                    "Toda modificacion queda sujeta a disponibilidad, aceptacion del prestador y pago de diferencias tarifarias. La agencia no garantiza que los servicios admitan cambios una vez emitidos.")
                + cond("8. Cancelaciones o cambios operativos de terceros",
                    "La agencia no resulta responsable por cancelaciones, demoras, huelgas, condiciones climaticas, disposiciones gubernamentales o fuerza mayor.")
                + cond("9. Servicios aereos",
                    "Los servicios aereos se rigen por las condiciones de la aerolinea emisora. El no show puede provocar la perdida de tramos siguientes o del ticket completo.")
                + cond("10. Reintegros",
                    "Todo reintegro queda sujeto a autorizacion y liquidacion del prestador, deduccion de penalidades e impuestos no reintegrables.")
                + cond("11. Datos del pasajero y documentacion",
                    "Es responsabilidad del pasajero verificar que sus datos personales sean correctos y coincidan con su documentacion de viaje.")
                + cond("12. Presentacion y no show",
                    "La falta de presentacion en horario podra implicar la perdida total del servicio sin derecho a reintegro.")
                + cond("13. Intermediacion",
                    "YES TRAVEL actua como intermediaria y gestora comercial de los servicios turisticos contratados.")
                + "<p style=\"margin:8px 0 2px;\"><b>14. Jurisdiccion / comunicaciones</b></p>"
                + "<p style=\"margin:0;\">Toda comunicacion por WhatsApp, email, vouchers o facturas vinculados a esta operacion forma parte de la contratacion. El pasajero constituye como validos los datos de contacto informados al momento de la reserva.</p>"
                + "</div>";
    }

    private String cond(String title, String body) {
        return "<p style=\"margin:8px 0 2px;\"><b>" + title + "</b></p>"
                + "<p style=\"margin:0 0 4px;\">" + body + "</p>";
    }

    private Long readTitularMemberId(TravelGroup g) {
        if (g == null) return null;
        Map<String, String> prefs = g.getCommonPrefs();
        if (prefs == null) return null;
        String v = prefs.get("paymentTitularMemberId");
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return null; }
    }

    private String safe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
