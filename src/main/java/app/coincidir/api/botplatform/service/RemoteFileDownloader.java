package app.coincidir.api.botplatform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RemoteFileDownloader — descarga archivos públicos desde URLs, con transformaciones
 * especiales para Google Drive y Dropbox (links de "compartir" → URLs de descarga).
 *
 * Límites:
 *   - Tamaño máximo: 50 MB (archivos más grandes se rechazan; no es sensato
 *     inyectarlos en el prompt del bot de todas formas).
 *   - Timeout: 60 segundos.
 *   - Solo acepta HTTP 200 como respuesta válida (redirects se siguen hasta 5).
 *
 * Notas sobre Drive:
 *   - URLs de "compartir" se convierten a /uc?export=download&id=...
 *   - Google Docs nativos (sin export) NO son soportados — devuelven HTML en
 *     vez del archivo. El usuario debe exportarlos a PDF/DOCX primero.
 */
@Slf4j
@Service
public class RemoteFileDownloader {

    private static final long MAX_SIZE = 50L * 1024 * 1024; // 50 MB
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // Regexes para detectar Drive en distintas formas de URL pública
    private static final Pattern DRIVE_FILE_ID = Pattern.compile(
            "drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?.*id=)([A-Za-z0-9_-]+)"
    );
    // Google Sheets nativas — se exportan como .xlsx
    private static final Pattern GDOCS_SHEET_ID = Pattern.compile(
            "docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]+)"
    );
    // Google Docs nativos — se exportan como .docx
    private static final Pattern GDOCS_DOC_ID = Pattern.compile(
            "docs\\.google\\.com/document/d/([A-Za-z0-9_-]+)"
    );
    // Google Slides nativos — se exportan como .pdf (docx no soportado por Slides)
    private static final Pattern GDOCS_SLIDE_ID = Pattern.compile(
            "docs\\.google\\.com/presentation/d/([A-Za-z0-9_-]+)"
    );

    public static class DownloadResult {
        public final byte[] content;
        public final String filename;
        public final String mimeType;

        public DownloadResult(byte[] content, String filename, String mimeType) {
            this.content = content;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    /**
     * Descarga desde URL. Retorna null si falla.
     * Transforma Drive/Dropbox automáticamente a URL de descarga directa.
     * Tira IllegalArgumentException con mensaje claro si la URL tiene un
     * formato no soportado (ej: carpeta de Drive, Google Docs nativo).
     */
    public DownloadResult download(String url) {
        if (url == null || url.isBlank()) return null;

        // Validaciones de formato ANTES de normalizar — errores claros
        if (url.contains("drive.google.com/drive/folders/")) {
            throw new IllegalArgumentException(
                "La URL es una CARPETA de Google Drive, no un archivo. " +
                "Abrí el archivo específico que querés usar (PDF, Excel, etc), " +
                "compartilo como 'Cualquiera con el link' y copiá esa URL " +
                "(tiene '/file/d/' en la dirección).");
        }
        // Google Docs/Sheets/Slides nativos: los transformamos automáticamente
        // a la URL de exportación pública (sólo funciona si están compartidos
        // como "Cualquiera con el link"). Se maneja en normalizeUrl().

        String effectiveUrl = normalizeUrl(url.trim());
        log.info("[RemoteDownload] URL normalizada: {} → {}", url, effectiveUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Coincidir-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("[RemoteDownload] status={} url={}", response.statusCode(), effectiveUrl);
                return null;
            }

            byte[] content = response.body();
            if (content == null || content.length == 0) {
                log.warn("[RemoteDownload] respuesta vacía");
                return null;
            }
            if (content.length > MAX_SIZE) {
                log.warn("[RemoteDownload] archivo supera 50MB ({} bytes)", content.length);
                return null;
            }

            // Detectar si nos devolvieron HTML en vez del archivo (ocurre cuando
            // el archivo no es público, o Drive pide confirmación por tamaño, o
            // un Google Doc nativo no está compartido).
            if (isHtmlResponse(content, response.headers().firstValue("Content-Type").orElse(""))) {
                log.warn("[RemoteDownload] respuesta HTML en vez de archivo binario — URL no pública o archivo inaccesible");
                throw new IllegalArgumentException(
                    "El archivo no es accesible públicamente. Verificá que esté compartido " +
                    "como 'Cualquiera con el link' (no sólo 'personas específicas'). " +
                    "Si es un Google Doc/Sheet/Slides, también tiene que estar público.");
            }

            String filename = extractFilename(response, effectiveUrl);
            String mimeType = response.headers().firstValue("Content-Type").orElse(null);
            if (mimeType != null) {
                // Algunos servidores devuelven "application/pdf; charset=utf-8" — limpiamos
                int semi = mimeType.indexOf(';');
                if (semi > 0) mimeType = mimeType.substring(0, semi).trim();
            }

            return new DownloadResult(content, filename, mimeType);

        } catch (Exception e) {
            log.warn("[RemoteDownload] error: {}", e.getMessage());
            return null;
        }
    }

    /** Transforma URLs de Drive/Dropbox/Google Docs a URLs de descarga directa. */
    public static String normalizeUrl(String url) {
        if (url == null) return null;

        // Google Drive archivo subido → descarga directa
        Matcher m = DRIVE_FILE_ID.matcher(url);
        if (m.find()) {
            String fileId = m.group(1);
            return "https://drive.google.com/uc?export=download&id=" + fileId;
        }

        // Google Sheets nativo → export como .xlsx
        m = GDOCS_SHEET_ID.matcher(url);
        if (m.find()) {
            String id = m.group(1);
            return "https://docs.google.com/spreadsheets/d/" + id + "/export?format=xlsx";
        }

        // Google Docs nativo → export como .docx
        m = GDOCS_DOC_ID.matcher(url);
        if (m.find()) {
            String id = m.group(1);
            return "https://docs.google.com/document/d/" + id + "/export?format=docx";
        }

        // Google Slides nativo → export como .pdf
        m = GDOCS_SLIDE_ID.matcher(url);
        if (m.find()) {
            String id = m.group(1);
            return "https://docs.google.com/presentation/d/" + id + "/export/pdf";
        }

        // Dropbox: ?dl=0 → ?dl=1
        if (url.contains("dropbox.com")) {
            if (url.contains("?dl=0")) return url.replace("?dl=0", "?dl=1");
            if (url.contains("&dl=0")) return url.replace("&dl=0", "&dl=1");
            if (!url.contains("dl=1") && !url.contains("raw=1")) {
                return url + (url.contains("?") ? "&" : "?") + "dl=1";
            }
        }

        return url;
    }

    /** Extrae filename del Content-Disposition o de la URL. */
    private static String extractFilename(HttpResponse<byte[]> response, String url) {
        String cd = response.headers().firstValue("Content-Disposition").orElse("");
        // Content-Disposition: attachment; filename="catalog.pdf"
        Pattern p = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";\\n]+)\"?", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(cd);
        if (m.find()) {
            String fn = m.group(1).trim();
            if (!fn.isEmpty()) return fn;
        }
        // Fallback: último segmento del path de la URL
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.contains("/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isEmpty() && last.length() < 200) return last;
            }
        } catch (Exception ignored) {}
        return "remote_file";
    }

    /** Detecta si el servidor nos devolvió HTML en lugar del archivo binario pedido. */
    private static boolean isHtmlResponse(byte[] content, String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            // Los primeros bytes de una página HTML empiezan con <!DOCTYPE o similar
            if (content.length > 20) {
                String head = new String(content, 0, Math.min(500, content.length)).toLowerCase();
                return head.contains("<html") || head.contains("<!doctype");
            }
        }
        return false;
    }
}
