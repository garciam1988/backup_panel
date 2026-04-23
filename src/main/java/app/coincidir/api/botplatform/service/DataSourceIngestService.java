package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * DataSourceIngestService — convierte bytes de un archivo en texto plano.
 *
 * Soporta:
 *   - PDF          → Apache PDFBox
 *   - DOCX (Word)  → Apache POI (XWPF)
 *   - TXT / MD     → decodificación directa UTF-8
 *   - Imágenes     → OCR con Claude Vision (Haiku)
 *
 * Excel/CSV NO se procesan acá — siguen manejándose en ExcelCatalogService
 * porque necesitan estructura tabular (rows, sheets, columns). Este servicio
 * produce texto para el Modo A (inyectar al prompt).
 *
 * El llamador es responsable de setear el resultado en ExcelCatalog.extractedText
 * y guardar la entidad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceIngestService {

    private final ExcelCatalogRepository catalogRepo;
    private final ObjectMapper objectMapper;

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // ────────────────────────────────────────────────────────────────────
    // Resultado del parsing
    // ────────────────────────────────────────────────────────────────────
    public static class IngestResult {
        public final String extractedText;
        public final Integer tokenCount;

        public IngestResult(String text, Integer tokens) {
            this.extractedText = text;
            this.tokenCount = tokens;
        }
    }

    /**
     * Extrae texto del archivo según su tipo MIME.
     * Retorna null si el tipo no es soportado o el parsing falla.
     */
    public IngestResult extractText(byte[] content, String filename, String mimeType) {
        if (content == null || content.length == 0) return null;

        String mt = normalizeMime(mimeType, filename);

        try {
            String text = switch (mt) {
                case "application/pdf" -> extractPdf(content);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractDocx(content);
                case "text/plain", "text/markdown" -> new String(content, StandardCharsets.UTF_8);
                case "image/jpeg", "image/png", "image/webp", "image/gif" -> extractImageOcr(content, mt);
                default -> {
                    log.info("[DataSource] Tipo {} no soportado para extracción de texto", mt);
                    yield null;
                }
            };
            if (text == null || text.isBlank()) return null;
            int tokens = estimateTokens(text);
            return new IngestResult(text.trim(), tokens);
        } catch (Exception e) {
            log.warn("[DataSource] Error extrayendo texto de {}: {}", filename, e.getMessage());
            return null;
        }
    }

    /** Si el mime type no viene, lo deducimos de la extensión del archivo. */
    public static String normalizeMime(String mime, String filename) {
        if (mime != null && !mime.isBlank() && !mime.equalsIgnoreCase("application/octet-stream")) {
            return mime.toLowerCase().trim();
        }
        if (filename == null) return "application/octet-stream";
        String n = filename.toLowerCase();
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".txt"))  return "text/plain";
        if (n.endsWith(".md") || n.endsWith(".markdown")) return "text/markdown";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm"))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".csv"))  return "text/csv";
        return "application/octet-stream";
    }

    /** true si el mime type corresponde a una tabla (Excel/CSV). Esos se delegan a ExcelCatalogService. */
    public static boolean isTabular(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase();
        return m.startsWith("application/vnd.openxmlformats-officedocument.spreadsheet")
            || m.startsWith("application/vnd.ms-excel")
            || m.equals("text/csv");
    }

    // ────────────────────────────────────────────────────────────────────
    // PDF
    // ────────────────────────────────────────────────────────────────────
    private String extractPdf(byte[] content) throws Exception {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Word (.docx)
    // ────────────────────────────────────────────────────────────────────
    private String extractDocx(byte[] content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor ext = new XWPFWordExtractor(doc)) {
            return ext.getText();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // OCR con Claude Vision
    // ────────────────────────────────────────────────────────────────────
    private String extractImageOcr(byte[] content, String mimeType) throws Exception {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.warn("[DataSource] OCR pedido pero no hay anthropic-key configurada");
            return null;
        }
        if (content.length > 5 * 1024 * 1024) {
            log.warn("[DataSource] imagen >5MB, Claude Vision puede rechazarla");
        }
        String base64 = Base64.getEncoder().encodeToString(content);

        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", HAIKU_MODEL);
        req.put("max_tokens", 4000);
        req.put("temperature", 0.0);
        req.put("system",
            "You are an OCR engine. Extract ALL text visible in the image, preserving " +
            "structure (lists, columns, prices, tables). Output the raw extracted text " +
            "with no commentary, no markdown fences, no explanations. If the image is " +
            "a menu/price list, preserve item names and prices clearly. If it's a document, " +
            "preserve paragraphs and headings. If the image has no text, output exactly: " +
            "[No text detected in image]");

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode contentArr = objectMapper.createArrayNode();

        ObjectNode imgBlock = objectMapper.createObjectNode();
        imgBlock.put("type", "image");
        ObjectNode src = objectMapper.createObjectNode();
        src.put("type", "base64");
        src.put("media_type", mimeType);
        src.put("data", base64);
        imgBlock.set("source", src);
        contentArr.add(imgBlock);

        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "Extract all text visible in this image.");
        contentArr.add(textBlock);

        userMsg.set("content", contentArr);
        messages.add(userMsg);
        req.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                .build();

        HttpResponse<String> r = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            log.warn("[DataSource] Claude Vision status={} body={}", r.statusCode(), truncate(r.body(), 300));
            return null;
        }
        JsonNode root = objectMapper.readTree(r.body());
        JsonNode ctn = root.path("content");
        if (!ctn.isArray() || ctn.isEmpty()) return null;
        return ctn.get(0).path("text").asText("").trim();
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────
    /** Estimación de tokens: ~4 chars por token (aproximación conservadora para Claude). */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 4);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
