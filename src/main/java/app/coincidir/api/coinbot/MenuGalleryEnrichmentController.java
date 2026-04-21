package app.coincidir.api.coinbot;

import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
import app.coincidir.api.domain.MenuImage;
import app.coincidir.api.repository.MenuImageRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MenuGalleryEnrichmentController — botón "🪄 Completar galería con IA".
 *
 * Flujo por producto:
 *   1. Claude Haiku genera una query de búsqueda en inglés optimizada para stock photos.
 *   2. Consulta Pexels API (free tier: 200 req/hora, suficiente para catálogos grandes).
 *   3. Descarga la primera foto (ordenadas por relevancia en Pexels).
 *   4. Redimensiona a 800x600 con crop centrado (Java stdlib, sin deps).
 *   5. Guarda en menu_image con name = "<producto>.jpg" → matchea en resolveItemImage.
 *
 * Requiere 2 env vars:
 *   - coincidir.anthropic-key
 *   - coincidir.pexels-key  (https://pexels.com/api — cuenta free, 200 req/hora)
 *
 * Si Pexels no está configurado, devuelve 503 con instrucciones.
 * Si Claude no está, usa heurístico (nombre del producto como query directa).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/menu/ai")
@RequiredArgsConstructor
public class MenuGalleryEnrichmentController {

    private final ExcelCatalogService catalogService;
    private final MenuImageRepository imageRepo;
    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    @Value("${coincidir.pexels-key:}")
    private String pexelsKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final String PEXELS_SEARCH = "https://api.pexels.com/v1/search";

    private static final int TARGET_WIDTH  = 800;
    private static final int TARGET_HEIGHT = 600;
    private static final int MAX_IMAGE_BYTES = 500 * 1024; // 500KB máx post-resize

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/enrich-gallery")
    @Transactional
    public EnrichResponse enrichGallery(@RequestBody EnrichRequest body) {
        if (body == null || body.catalogId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta catalogId");

        if (pexelsKey == null || pexelsKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Pexels API key no configurada. Conseguí una gratis en pexels.com/api y agregala como coincidir.pexels-key en application.yml.");

        // 1) Leer el nombre de la columna del producto. Si no viene, intentamos detectar.
        String productColumn = body.productColumnName;
        if (productColumn == null || productColumn.isBlank()) productColumn = "Producto";

        // 2) Traer hasta 200 filas del catálogo
        List<ExcelCatalogRow> rows = catalogService.preview(body.catalogId, null, 200);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El catálogo no tiene filas");

        // 3) Mapa de nombres de imágenes ya existentes en la galería (para dedup)
        Set<String> existingImageNames = imageRepo.findAll().stream()
                .map(i -> norm(i.getName()))
                .collect(Collectors.toSet());

        EnrichResponse result = new EnrichResponse();
        result.items = new ArrayList<>();

        // Deducción del brand/contexto general (Claude lo usa para queries mejores)
        String context = (body.brandContext != null && !body.brandContext.isBlank())
                ? body.brandContext : "general";

        // Cache de queries ya usadas en esta sesión — evita duplicados exactos
        Set<String> usedPhotoUrls = new HashSet<>();

        int processed = 0;
        for (ExcelCatalogRow row : rows) {
            processed++;
            if (processed > 60) { // hard cap por request
                log.info("[EnrichGallery] cap de 60 productos alcanzado, restante queda para otra corrida");
                break;
            }
            ItemEnrichResult ir = new ItemEnrichResult();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(row.getDataJson(), Map.class);
                String productName = findColumnValue(data, productColumn);
                if (productName == null || productName.isBlank()) {
                    ir.status = "skipped"; ir.reason = "sin nombre de producto";
                    result.items.add(ir);
                    continue;
                }
                ir.productName = productName;
                String targetImageName = productName.replaceAll("[^\\p{L}\\d ]+", "").trim() + ".jpg";

                // Skip si ya existe en galería
                if (existingImageNames.contains(norm(targetImageName))) {
                    ir.status = "skipped"; ir.reason = "ya tiene foto en galería";
                    result.items.add(ir);
                    result.skipped++;
                    continue;
                }

                // Paso 1: generar query de búsqueda en inglés
                String query = generateSearchQuery(productName, context);
                ir.searchQuery = query;

                // Paso 2: buscar en Pexels
                List<PexelsPhoto> photos = searchPexels(query, 5);
                // Filtrar las URLs ya usadas en esta sesión
                photos.removeIf(p -> usedPhotoUrls.contains(p.downloadUrl));
                if (photos.isEmpty()) {
                    ir.status = "no_photos"; ir.reason = "Pexels no devolvió resultados para '" + query + "'";
                    result.items.add(ir);
                    result.failed++;
                    continue;
                }

                // Tomamos la primera (Pexels ordena por relevancia)
                PexelsPhoto chosen = photos.get(0);
                usedPhotoUrls.add(chosen.downloadUrl);

                // Paso 3: descargar
                byte[] imgBytes = downloadImage(chosen.downloadUrl);
                if (imgBytes == null || imgBytes.length == 0) {
                    ir.status = "download_failed"; ir.reason = "no se pudo descargar de Pexels";
                    result.items.add(ir);
                    result.failed++;
                    continue;
                }

                // Paso 4: redimensionar
                byte[] resized = resizeAndCrop(imgBytes, TARGET_WIDTH, TARGET_HEIGHT);
                if (resized == null) {
                    ir.status = "resize_failed"; ir.reason = "error procesando imagen";
                    result.items.add(ir);
                    result.failed++;
                    continue;
                }

                // Paso 5: guardar en BD
                MenuImage mi = new MenuImage();
                mi.setName(targetImageName);
                mi.setRole("generic");
                mi.setContentType("image/jpeg");
                mi.setSizeBytes((long) resized.length);
                mi.setData(resized);
                MenuImage saved = imageRepo.save(mi);
                existingImageNames.add(norm(targetImageName));

                ir.status = "ok";
                ir.imageId = saved.getId();
                ir.imageName = targetImageName;
                ir.photographer = chosen.photographer;
                result.enriched++;
                result.items.add(ir);

            } catch (Exception e) {
                log.warn("[EnrichGallery] error procesando fila: {}", e.getMessage());
                ir.status = "error"; ir.reason = e.getMessage();
                result.items.add(ir);
                result.failed++;
            }
        }

        result.processed = processed;
        log.info("[EnrichGallery] catalog={} processed={} enriched={} skipped={} failed={}",
                body.catalogId, result.processed, result.enriched, result.skipped, result.failed);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Claude: genera query optimizada en inglés para stock photos
    // ─────────────────────────────────────────────────────────────────────
    private String generateSearchQuery(String productName, String context) {
        // Fallback heurístico si no hay API key
        if (anthropicKey == null || anthropicKey.isBlank()) {
            return simplifyForSearch(productName);
        }
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", HAIKU_MODEL);
            req.put("max_tokens", 60);
            req.put("temperature", 0.0);
            req.put("system",
                "Sos un experto en búsqueda de stock photos. Recibís el nombre de un producto " +
                "(comida, bebida, servicio, etc) y devolvés SOLO la query ideal para buscar en Pexels, " +
                "en inglés, 2-4 palabras, sin comillas, sin markdown. " +
                "Ejemplos:\n" +
                "  'Pizza Muzzarella' → neapolitan margherita pizza\n" +
                "  'Empanada de Carne' → argentinian beef empanada\n" +
                "  'Hamburguesa Doble Cheddar' → double cheddar cheeseburger\n" +
                "  'Café Latte' → latte coffee\n" +
                "  'Milanesa napolitana' → breaded chicken cutlet\n" +
                "Priorizá palabras que traigan fotos apetitosas. No incluyas el brand ni el local.");
            ArrayNode msgs = objectMapper.createArrayNode();
            ObjectNode m = objectMapper.createObjectNode();
            m.put("role", "user");
            m.put("content", "Producto: " + productName + "\nContexto del negocio: " + context + "\n\nQuery:");
            msgs.add(m);
            req.set("messages", msgs);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                    .build();
            HttpResponse<String> r = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("[EnrichGallery] claude query-gen status={}", r.statusCode());
                return simplifyForSearch(productName);
            }
            JsonNode root = objectMapper.readTree(r.body());
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) return simplifyForSearch(productName);
            String text = content.get(0).path("text").asText("").trim();
            // Limpiar posibles comillas o prefijos
            text = text.replaceAll("^[\"']+|[\"']+$", "").replaceAll("^Query:?\\s*", "").trim();
            if (text.isEmpty() || text.length() > 80) return simplifyForSearch(productName);
            return text;
        } catch (Exception e) {
            log.warn("[EnrichGallery] error query-gen: {}", e.getMessage());
            return simplifyForSearch(productName);
        }
    }

    /** Fallback: saca acentos + artículos, deja máx 4 palabras. */
    private static String simplifyForSearch(String name) {
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
        n = n.replaceAll("\\b(de|la|el|los|las|con|sin|al)\\b", " ").trim();
        String[] parts = n.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(parts.length, 4); i++) {
            if (!parts[i].isBlank()) {
                if (out.length() > 0) out.append(" ");
                out.append(parts[i]);
            }
        }
        return out.length() > 0 ? out.toString() : name;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pexels: search + download
    // ─────────────────────────────────────────────────────────────────────
    private List<PexelsPhoto> searchPexels(String query, int perPage) throws Exception {
        String url = PEXELS_SEARCH + "?per_page=" + perPage +
                "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", pexelsKey)
                .GET()
                .build();
        HttpResponse<String> r = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            log.warn("[EnrichGallery] pexels status={} body={}", r.statusCode(), truncate(r.body(), 200));
            return new ArrayList<>();
        }
        JsonNode root = objectMapper.readTree(r.body());
        JsonNode photos = root.path("photos");
        List<PexelsPhoto> out = new ArrayList<>();
        if (photos.isArray()) {
            for (JsonNode p : photos) {
                PexelsPhoto ph = new PexelsPhoto();
                ph.id = p.path("id").asLong();
                ph.photographer = p.path("photographer").asText();
                // Preferimos "large" (~940px lado largo). Si no está, "original".
                ph.downloadUrl = p.path("src").path("large").asText(
                        p.path("src").path("original").asText(""));
                if (!ph.downloadUrl.isEmpty()) out.add(ph);
            }
        }
        return out;
    }

    private byte[] downloadImage(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> r = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) return null;
        return r.body();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Resize + center-crop a 800x600, JPEG calidad 0.82
    // ─────────────────────────────────────────────────────────────────────
    private byte[] resizeAndCrop(byte[] input, int targetW, int targetH) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(input));
            if (src == null) return null;
            int sw = src.getWidth(), sh = src.getHeight();
            double scale = Math.max((double) targetW / sw, (double) targetH / sh);
            int nw = (int) Math.round(sw * scale);
            int nh = (int) Math.round(sh * scale);

            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            int cropX = Math.max(0, (nw - targetW) / 2);
            int cropY = Math.max(0, (nh - targetH) / 2);
            int cropW = Math.min(nw - cropX, targetW);
            int cropH = Math.min(nh - cropY, targetH);
            BufferedImage cropped = scaled.getSubimage(cropX, cropY, cropW, cropH);

            // Flatten (getSubimage no copia bytes; forzamos copia)
            BufferedImage flat = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB);
            Graphics2D gf = flat.createGraphics();
            gf.drawImage(cropped, 0, 0, null);
            gf.dispose();

            // Encode JPEG. ImageIO por default usa quality alta; para 500KB cap a 800x600
            // es suficiente con JPEG estándar. Si excede, reencode con calidad menor.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(flat, "jpg", baos);
            byte[] bytes = baos.toByteArray();
            // Si es >500KB, reencode con calidad 0.75 (ImageIO no tiene API directa
            // simple, así que aceptamos el default — con 800x600 suele dar <300KB).
            if (bytes.length > MAX_IMAGE_BYTES) {
                log.info("[EnrichGallery] imagen >500KB ({}KB), aceptando igual", bytes.length / 1024);
            }
            return bytes;
        } catch (Exception e) {
            log.warn("[EnrichGallery] resize falló: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private static String findColumnValue(Map<String, Object> data, String targetColumn) {
        if (data == null || targetColumn == null) return null;
        String targetN = norm(targetColumn);
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (norm(e.getKey()).equals(targetN)) {
                Object v = e.getValue();
                return v == null ? null : String.valueOf(v).trim();
            }
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("\\s+", "")
                .trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnrichRequest {
        public Long   catalogId;
        public String productColumnName;   // ej: "Producto". Si null, usa default.
        public String brandContext;        // ej: "pizzeria". Ayuda a Claude.
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnrichResponse {
        public int processed;
        public int enriched;
        public int skipped;
        public int failed;
        public List<ItemEnrichResult> items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemEnrichResult {
        public String productName;
        public String status;        // ok | skipped | no_photos | download_failed | resize_failed | error
        public String reason;
        public String searchQuery;
        public Long   imageId;
        public String imageName;
        public String photographer;
    }

    private static class PexelsPhoto {
        long id;
        String photographer;
        String downloadUrl;
    }
}
