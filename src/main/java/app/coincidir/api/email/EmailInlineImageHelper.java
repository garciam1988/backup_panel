package app.coincidir.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.MessagingException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EmailInlineImageHelper — Convierte data URLs embebidas en HTML de emails
 * (típicamente logos en base64) en imágenes inline referenciadas por CID,
 * para que Gmail las renderice correctamente.
 *
 * Por qué existe:
 *   Gmail (web y app) bloquea por seguridad las imágenes `<img src="data:...">`
 *   embebidas como data URLs en el body del email. Esto produce el ícono de
 *   "imagen rota" en ~40% del mercado. Apple Mail, Outlook desktop, Thunderbird
 *   y la mayoría de los clientes desktop SÍ las renderizan, pero la
 *   inconsistencia entre clientes es inaceptable.
 *
 *   La solución estándar es CID embedding: la imagen viaja como parte del MIME
 *   del mensaje (no como recurso externo ni como data URL), y el HTML la
 *   referencia con `src="cid:identificador"`. Gmail SÍ renderiza este formato.
 *
 * Uso típico:
 *   InlineImageResult r = EmailInlineImageHelper.extract(htmlOriginal);
 *   helper.setText(r.html, true);
 *   for (InlineImage img : r.images) {
 *       helper.addInline(img.cid, new ByteArrayResource(img.bytes), img.contentType);
 *   }
 *
 * El método {@link #applyInline} encapsula este patrón para callers que no
 * tengan que personalizar nada entre setText y addInline.
 *
 * Compatibilidad:
 *   - Si el HTML no tiene data URLs, devuelve el HTML tal cual y lista vacía.
 *     Los emails que ya andaban bien siguen andando igual.
 *   - URLs externas (http://, https://) NO se tocan — el regex solo matchea
 *     `data:image/...`.
 *   - Soporta múltiples imágenes en el mismo HTML (cada una con su propio CID).
 *   - Si una data URL tiene base64 inválido, se deja como estaba (no rompe el
 *     envío del email entero por una imagen mala).
 */
@Slf4j
public final class EmailInlineImageHelper {

    private EmailInlineImageHelper() {} // utility class

    /**
     * Resultado de procesar un HTML: el HTML transformado (con src="cid:...")
     * y la lista de imágenes inline a adjuntar al MIME.
     */
    public static final class InlineImageResult {
        public final String html;
        public final List<InlineImage> images;

        public InlineImageResult(String html, List<InlineImage> images) {
            this.html = html;
            this.images = images;
        }
    }

    /** Una imagen inline a adjuntar con MimeMessageHelper.addInline. */
    public static final class InlineImage {
        public final String cid;
        public final byte[] bytes;
        public final String contentType;

        public InlineImage(String cid, byte[] bytes, String contentType) {
            this.cid = cid;
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }

    // Pattern para `src="data:image/xxx;base64,YYY"`. Soporta:
    //   - comillas dobles y simples
    //   - subtipos arbitrarios (png, jpeg, svg+xml, webp, etc)
    //   - parámetros opcionales entre el subtipo y la coma (ej: ;charset=utf-8)
    //   - whitespace en el payload base64 (algunos generadores agregan saltos)
    private static final Pattern DATA_URL_PATTERN = Pattern.compile(
        "src\\s*=\\s*([\"'])(data:image/([a-zA-Z0-9+.-]+)(?:;[^,\"']*)?,([A-Za-z0-9+/=\\s]+?))\\1",
        Pattern.DOTALL
    );

    /**
     * Escanea el HTML en busca de data URLs embebidas y las convierte en
     * referencias CID. NO modifica el MIME — solo devuelve el HTML transformado
     * + la lista de imágenes a adjuntar. El caller las agrega con addInline.
     */
    public static InlineImageResult extract(String html) {
        if (html == null || html.isEmpty()) {
            return new InlineImageResult(html, List.of());
        }
        Matcher m = DATA_URL_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        List<InlineImage> images = new ArrayList<>();
        int counter = 0;
        while (m.find()) {
            String quote = m.group(1);
            String mimeSubtype = m.group(3);
            String base64Data = m.group(4).replaceAll("\\s", ""); // limpiar whitespace
            try {
                byte[] bytes = Base64.getDecoder().decode(base64Data);
                String cid = "inline-img-" + (++counter);
                String contentType = "image/" + mimeSubtype.toLowerCase(Locale.ROOT);
                images.add(new InlineImage(cid, bytes, contentType));
                String replacement = "src=" + quote + "cid:" + cid + quote;
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } catch (IllegalArgumentException e) {
                // base64 inválido — dejar el src tal cual, no romper el resto.
                log.debug("data URL malformada en HTML, se deja como está: {}", e.getMessage());
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(out);
        return new InlineImageResult(out.toString(), images);
    }

    /**
     * Atajo para el caso típico: aplicar el HTML al MimeMessageHelper, extraer
     * data URLs y adjuntarlas como inline. Devuelve cuántas imágenes inline se
     * agregaron (útil para logging).
     *
     * IMPORTANTE: este método llama a {@code helper.setText(...)} con isHtml=true.
     * NO llamar a setText antes ni después; este método se encarga de eso.
     */
    public static int applyInline(MimeMessageHelper helper, String html) throws MessagingException {
        InlineImageResult r = extract(html);
        helper.setText(r.html, true);
        // addInline DEBE invocarse DESPUÉS de setText. Si no, Spring Mail arma
        // el MIME en orden incorrecto y el cliente de email no resuelve los CID.
        for (InlineImage img : r.images) {
            helper.addInline(img.cid, new ByteArrayResource(img.bytes), img.contentType);
        }
        return r.images.size();
    }
}
