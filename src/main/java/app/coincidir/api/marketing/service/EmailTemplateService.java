package app.coincidir.api.marketing.service;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * EmailTemplateService — Wraps el cuerpo HTML que escribió el operador en una
 * plantilla profesional con cabecera de marca + footer con unsubscribe.
 *
 * Por qué tabla y no CSS Grid/Flex:
 *   Outlook (~6% del mercado de email todavía) usa MSO Word como motor de
 *   render. No soporta CSS moderno. La ÚNICA forma de que un email se vea
 *   bien en TODOS los clientes (Gmail, Apple Mail, Outlook, Yahoo, etc) es
 *   usar <table>+inline styles. Es feo pero es la realidad del email desde
 *   hace 25 años.
 *
 * Diseño:
 *   - Header con secondaryColor del programa + logo + brand name.
 *   - Saludo "Hola {firstName}".
 *   - Cuerpo del usuario (HTML escapeado o tal cual si confiamos).
 *   - Botón CTA opcional (si la campaña tiene ctaUrl).
 *   - Caja de cupón opcional (si la campaña tiene couponId).
 *   - Footer con disclaimer + link unsubscribe (en próxima iter).
 *
 * Variables que renderea (mismas que el dispatcher):
 *   {firstName}, {lastName}, {fullName}, {couponCode}, {ctaUrl}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final BotConfigRepository botConfigRepo;
    private final LoyaltyProgramService programService;

    /**
     * Renderiza la plantilla completa. Retorna el HTML listo para enviar.
     *
     * @param customer       cliente destinatario
     * @param renderedBody   cuerpo HTML ya con las variables resueltas
     * @param ctaUrl         URL del botón (puede ser null)
     * @param coupon         cupón asociado a la campaña (puede ser null)
     * @return HTML completo listo para JavaMailSender
     */
    public String render(LoyaltyCustomer customer,
                         String renderedBody,
                         String ctaUrl,
                         Coupon coupon) {
        BotConfig cfg = botConfigRepo.findById(1L).orElse(null);
        LoyaltyProgram program = programService.getActiveProgram();

        String brandName = cfg != null && cfg.getBrandName() != null
            ? cfg.getBrandName() : (program != null ? program.getName() : "Mi negocio");
        String logoUrl = cfg != null ? cfg.getLogoUrl() : null;

        String secondaryColor = extractSecondaryColor(program);
        String primaryColor = extractPrimaryColor(program);

        String firstName = customer.getFirstName() == null ? "" : customer.getFirstName();

        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">")
            .append("<html xmlns=\"http://www.w3.org/1999/xhtml\">")
            .append("<head>")
            .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>")
            .append("<title>").append(escape(brandName)).append("</title>")
            .append("<style>")
            .append("body { margin: 0; padding: 0; background: #f1f5f9; }")
            .append("table { border-collapse: collapse; }")
            .append("img { display: block; border: 0; }")
            .append("a { text-decoration: none; }")
            .append("@media only screen and (max-width: 600px) {")
            .append("  .container { width: 100% !important; }")
            .append("  .padded { padding: 20px !important; }")
            .append("}")
            .append("</style>")
            .append("</head>")
            .append("<body style=\"margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">");

        // Wrapper exterior centrado
        html.append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"100%\" style=\"background:#f1f5f9;\">");
        html.append("<tr><td align=\"center\" style=\"padding:30px 12px;\">");

        // Contenedor principal 600px
        html.append("<table role=\"presentation\" class=\"container\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"600\" style=\"max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 4px 20px rgba(15,39,86,0.08);\">");

        // Header
        html.append("<tr><td style=\"background:").append(secondaryColor).append(";padding:24px 32px;color:#ffffff;\">")
            .append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"100%\">")
            .append("<tr>");
        if (logoUrl != null && !logoUrl.isBlank()) {
            html.append("<td width=\"56\" style=\"padding-right:14px;vertical-align:middle;\">")
                .append("<img src=\"").append(escapeAttr(logoUrl)).append("\" width=\"48\" height=\"48\" alt=\"\" style=\"width:48px;height:48px;border-radius:10px;object-fit:cover;\"/>")
                .append("</td>");
        }
        html.append("<td style=\"vertical-align:middle;\">")
            .append("<div style=\"font-size:20px;font-weight:700;line-height:1.2;letter-spacing:-0.01em;\">").append(escape(brandName)).append("</div>")
            .append("</td>")
            .append("</tr></table></td></tr>");

        // Body
        html.append("<tr><td class=\"padded\" style=\"padding:32px 32px 24px;color:#1e293b;\">");

        if (!firstName.isBlank()) {
            html.append("<div style=\"font-size:18px;font-weight:600;color:#0f172a;margin-bottom:18px;\">")
                .append("👋 Hola, ").append(escape(firstName))
                .append("</div>");
        }

        // El cuerpo del usuario va tal cual. Confiamos en el operador (es admin
        // autenticado del módulo Marketing). Asumimos que escribe HTML simple
        // o texto plano con líneas. Si es plain text, lo envolvemos en <p>.
        String bodyContent = wrapPlainTextIfNeeded(renderedBody);
        html.append("<div style=\"font-size:15px;line-height:1.6;color:#334155;\">")
            .append(bodyContent)
            .append("</div>");

        // Botón CTA si hay
        if (ctaUrl != null && !ctaUrl.isBlank()) {
            html.append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:28px auto 0;\">")
                .append("<tr><td align=\"center\" style=\"border-radius:10px;background:").append(primaryColor).append(";\">")
                .append("<a href=\"").append(escapeAttr(ctaUrl)).append("\" target=\"_blank\" style=\"display:inline-block;padding:14px 32px;color:#ffffff;font-size:15px;font-weight:700;text-decoration:none;border-radius:10px;\">")
                .append("Ver más →")
                .append("</a>")
                .append("</td></tr></table>");
        }

        // Caja de cupón si hay
        if (coupon != null) {
            html.append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:28px;\">")
                .append("<tr><td style=\"background:#f8fafc;border:2px dashed ").append(primaryColor).append(";border-radius:14px;padding:20px;text-align:center;\">")
                .append("<div style=\"font-size:11px;color:#64748b;text-transform:uppercase;letter-spacing:0.05em;font-weight:600;margin-bottom:6px;\">🎟️ Tu cupón de regalo</div>")
                .append("<div style=\"font-family:'Courier New',monospace;font-size:24px;font-weight:700;color:").append(primaryColor).append(";letter-spacing:0.08em;margin:6px 0;\">")
                .append(escape(coupon.getCode()))
                .append("</div>")
                .append("<div style=\"font-size:14px;color:#475569;font-weight:600;\">").append(escape(buildCouponDescription(coupon))).append("</div>")
                .append("</td></tr></table>");
        }

        html.append("</td></tr>");

        // Footer
        html.append("<tr><td style=\"background:#f8fafc;padding:20px 32px;border-top:1px solid #e2e8f0;\">")
            .append("<div style=\"font-size:12px;color:#64748b;line-height:1.5;text-align:center;\">")
            .append("Recibiste este email porque sos cliente de <strong>").append(escape(brandName)).append("</strong>.<br/>")
            .append("Si no querés recibir más, podés actualizar tus preferencias desde tu tarjeta digital.")
            .append("</div>")
            .append("</td></tr>");

        html.append("</table>"); // /container

        html.append("</td></tr></table>"); // /wrapper
        html.append("</body></html>");

        return html.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String extractSecondaryColor(LoyaltyProgram program) {
        if (program == null || program.getCardDesignJson() == null) return "#1D3557";
        try {
            JsonNode cd = new ObjectMapper().readTree(program.getCardDesignJson());
            String c = cd.path("secondaryColor").asText(null);
            return (c != null && c.startsWith("#")) ? c : "#1D3557";
        } catch (Exception e) {
            return "#1D3557";
        }
    }

    private String extractPrimaryColor(LoyaltyProgram program) {
        if (program == null || program.getCardDesignJson() == null) return "#E63946";
        try {
            JsonNode cd = new ObjectMapper().readTree(program.getCardDesignJson());
            String c = cd.path("primaryColor").asText(null);
            return (c != null && c.startsWith("#")) ? c : "#E63946";
        } catch (Exception e) {
            return "#E63946";
        }
    }

    /**
     * Si el body parece texto plano (no tiene tags HTML), lo envuelve en <p>
     * preservando saltos de línea. Si ya parece HTML, lo deja tal cual.
     */
    private String wrapPlainTextIfNeeded(String body) {
        if (body == null) return "";
        if (body.contains("<p>") || body.contains("<br") || body.contains("<div") || body.contains("<a ")) {
            return body;
        }
        // Plain text → preservar saltos de línea
        return "<p style=\"margin:0 0 12px;\">" + body.replace("\n\n", "</p><p style=\"margin:0 0 12px;\">")
                                                       .replace("\n", "<br/>") + "</p>";
    }

    private String buildCouponDescription(Coupon c) {
        switch (c.getDiscountType()) {
            case PERCENTAGE: return c.getDiscountValue() + "% de descuento";
            case FIXED:      return "$" + c.getDiscountValue() + " de descuento";
            case FREE_ITEM:  return "Producto gratis: " + (c.getFreeItemRef() == null ? "" : c.getFreeItemRef());
            case BOGO:       return "2x1";
            default:         return c.getName() == null ? "" : c.getName();
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("\"", "&quot;").replace("'", "&#39;");
    }
}
