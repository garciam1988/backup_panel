package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRowRepository;
import app.coincidir.api.botplatform.service.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

/**
 * DataSourceController — extensión del sistema de "Catálogos de Excel" para
 * soportar fuentes de datos genéricas (PDF, Word, TXT, imágenes) subidas por
 * upload directo o desde URL pública con auto-refresh.
 *
 * Endpoints:
 *   POST /api/admin/data-sources/upload        — upload genérico (cualquier tipo)
 *   POST /api/admin/data-sources/from-url      — descarga desde URL remota
 *   POST /api/admin/data-sources/{id}/refresh  — re-descarga una URL
 *   POST /api/admin/data-sources/test-url      — probar una URL antes de guardar
 *
 * Los demás endpoints (list, delete, preview) siguen funcionando via
 * /api/admin/excel-catalogs — las "fuentes" se guardan en la misma tabla.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/data-sources")
@RequiredArgsConstructor
public class DataSourceController {

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogRowRepository rowRepo;
    private final ExcelCatalogService catalogService;
    private final DataSourceIngestService ingestService;
    private final RemoteFileDownloader downloader;
    private final DataSourceMigrationService migrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────
    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    @Transactional
    public DataSourceDto upload(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws Exception {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre requerido");

        String uploadedBy = auth != null ? auth.getName() : "anonymous";
        String mime = DataSourceIngestService.normalizeMime(file.getContentType(), file.getOriginalFilename());
        return ingestFileBytes(name, description, file.getOriginalFilename(), file.getBytes(), mime, "file", null, null, uploadedBy);
    }

    @PostMapping(path = "/from-url")
    @Transactional
    public DataSourceDto fromUrl(@RequestBody UrlUploadRequest body, Authentication auth) throws Exception {
        if (body == null || body.url == null || body.url.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL requerida");
        if (body.name == null || body.name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre requerido");

        RemoteFileDownloader.DownloadResult dl;
        try {
            dl = downloader.download(body.url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (dl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo descargar el archivo. Verificá que la URL sea pública y el formato sea válido. " +
                    "Para Google Drive, asegurate de que esté compartido como 'Cualquiera con el link'.");
        }

        String uploadedBy = auth != null ? auth.getName() : "anonymous";
        String mime = DataSourceIngestService.normalizeMime(dl.mimeType, dl.filename);
        return ingestFileBytes(
                body.name, body.description, dl.filename, dl.content, mime,
                "url", body.url, body.autoRefreshHours, uploadedBy
        );
    }

    @PostMapping("/{id}/refresh")
    @Transactional
    public DataSourceDto refresh(@PathVariable Long id, Authentication auth) throws Exception {
        ExcelCatalog cat = catalogRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fuente no encontrada"));
        if (!"url".equals(cat.getSourceType()) || cat.getOriginalUrl() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta fuente no es de URL, no se puede refrescar");
        }

        RemoteFileDownloader.DownloadResult dl = downloader.download(cat.getOriginalUrl());
        if (dl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo re-descargar desde la URL. Verificá que siga siendo accesible.");
        }

        String uploadedBy = auth != null ? auth.getName() : "anonymous";
        String mime = DataSourceIngestService.normalizeMime(dl.mimeType, dl.filename);
        return ingestFileBytes(
                cat.getName(), cat.getDescription(), dl.filename, dl.content, mime,
                "url", cat.getOriginalUrl(), cat.getAutoRefreshHours(), uploadedBy
        );
    }

    @PostMapping("/test-url")
    public TestUrlResponse testUrl(@RequestBody TestUrlRequest body) {
        TestUrlResponse out = new TestUrlResponse();
        if (body == null || body.url == null || body.url.isBlank()) {
            out.ok = false;
            out.error = "URL vacía";
            return out;
        }
        try {
            RemoteFileDownloader.DownloadResult dl = downloader.download(body.url);
            if (dl == null) {
                out.ok = false;
                out.error = "No se pudo descargar. Verificá que la URL sea pública y el archivo accesible.";
                return out;
            }
            out.ok = true;
            out.detectedFilename = dl.filename;
            out.detectedMimeType = DataSourceIngestService.normalizeMime(dl.mimeType, dl.filename);
            out.sizeBytes = (long) dl.content.length;
            return out;
        } catch (IllegalArgumentException e) {
            // Errores de formato conocidos (carpeta Drive, Google Doc nativo, etc)
            out.ok = false;
            out.error = e.getMessage();
            return out;
        } catch (Exception e) {
            out.ok = false;
            out.error = "Error descargando: " + e.getMessage();
            return out;
        }
    }

    /**
     * Devuelve el texto consolidado de todas las fuentes activas para inyectar
     * al prompt del bot (Modo A). Respeta el límite máximo de tokens — si una
     * fuente excede, se omite con un marcador de warning.
     *
     * Es un endpoint público del bot (no admin), por eso vive también en
     * /api/coinbot/data-sources/prompt-context.
     */
    @GetMapping("/prompt-context")
    @Transactional(readOnly = true)
    public PromptContextResponse getPromptContext(
            @RequestParam(defaultValue = "40000") int maxTokens) {
        PromptContextResponse resp = new PromptContextResponse();
        resp.sources = new java.util.ArrayList<>();

        java.util.List<ExcelCatalog> active = catalogRepo.findByActiveTrueOrderByNameAsc();
        int usedTokens = 0;

        for (ExcelCatalog cat : active) {
            String text = cat.getExtractedText();

            // Si no hay texto extraído pero es tabular, lo serializamos desde las filas
            if ((text == null || text.isBlank()) && cat.getTotalRows() != null && cat.getTotalRows() > 0) {
                text = serializeTabularToText(cat);
            }

            if (text == null || text.isBlank()) continue;

            Integer tk = cat.getTokenCount();
            int t = (tk != null && tk > 0) ? tk : DataSourceIngestService.estimateTokens(text);

            PromptSourceEntry e = new PromptSourceEntry();
            e.id = cat.getId();
            e.name = cat.getName();
            e.tokenCount = t;

            if (usedTokens + t > maxTokens) {
                e.included = false;
                e.warning = "Excede el límite — no se inyectó al prompt (" + t + " tokens).";
                e.text = null;
            } else {
                e.included = true;
                e.text = text;
                usedTokens += t;
            }
            resp.sources.add(e);
        }
        resp.totalTokensUsed = usedTokens;
        resp.maxTokens = maxTokens;
        return resp;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core ingest: toma bytes, parsea, guarda
    // ─────────────────────────────────────────────────────────────────────
    private DataSourceDto ingestFileBytes(
            String name, String description, String originalFilename,
            byte[] content, String mimeType,
            String sourceType, String originalUrl, Integer autoRefreshHours,
            String uploadedBy
    ) throws Exception {
        name = name.trim();

        // Para Excel/CSV, delegamos al flujo existente (rows parseadas estructuradas)
        if (DataSourceIngestService.isTabular(mimeType)) {
            MultipartFile mf = new ByteArrayMultipartFile(
                    "file", originalFilename, mimeType, content);
            ExcelCatalog saved = catalogService.uploadCatalog(name, description, mf, uploadedBy);
            // Actualizar metadata de fuente
            saved.setSourceType(sourceType);
            saved.setMimeType(mimeType);
            saved.setOriginalUrl(originalUrl);
            saved.setAutoRefreshHours(autoRefreshHours);
            if ("url".equals(sourceType)) saved.setLastRefreshedAt(Instant.now());
            return toDto(catalogRepo.save(saved));
        }

        // Para los demás, extraemos texto y guardamos en extractedText
        Optional<ExcelCatalog> existing = catalogRepo.findByName(name);
        ExcelCatalog cat = existing.orElseGet(ExcelCatalog::new);
        cat.setName(name);
        if (description != null) cat.setDescription(description);
        cat.setOriginalFilename(originalFilename);
        cat.setSizeBytes((long) content.length);
        cat.setUploadedBy(uploadedBy);
        cat.setActive(true);
        cat.setSourceType(sourceType);
        cat.setMimeType(mimeType);
        cat.setOriginalUrl(originalUrl);
        cat.setAutoRefreshHours(autoRefreshHours);
        if ("url".equals(sourceType)) cat.setLastRefreshedAt(Instant.now());
        // Extraer texto (PDF/Word/TXT/OCR de imagen)
        DataSourceIngestService.IngestResult ir = ingestService.extractText(content, originalFilename, mimeType);
        if (ir != null) {
            cat.setExtractedText(ir.extractedText);
            cat.setTokenCount(ir.tokenCount);
        } else {
            cat.setExtractedText(null);
            cat.setTokenCount(0);
            log.warn("[DataSource] No se pudo extraer texto de {} (mime={})", originalFilename, mimeType);
        }
        // Para no-tabular, el sheetsJson queda vacío y totalRows=0
        if (cat.getSheetsJson() == null) cat.setSheetsJson("[]");
        cat.setTotalRows(0);
        return toDto(catalogRepo.save(cat));
    }

    private DataSourceDto toDto(ExcelCatalog c) {
        DataSourceDto d = new DataSourceDto();
        d.id                = c.getId();
        d.name              = c.getName();
        d.description       = c.getDescription();
        d.originalFilename  = c.getOriginalFilename();
        d.sizeBytes         = c.getSizeBytes();
        d.totalRows         = c.getTotalRows();
        d.active            = c.getActive();
        d.createdAt         = c.getCreatedAt();
        d.updatedAt         = c.getUpdatedAt();
        d.uploadedBy        = c.getUploadedBy();
        d.sourceType        = c.getSourceType();
        d.mimeType          = c.getMimeType();
        d.originalUrl       = c.getOriginalUrl();
        d.lastRefreshedAt   = c.getLastRefreshedAt();
        d.autoRefreshHours  = c.getAutoRefreshHours();
        d.tokenCount        = c.getTokenCount();
        d.hasExtractedText  = c.getExtractedText() != null && !c.getExtractedText().isBlank();
        return d;
    }

    /**
     * Convierte las filas estructuradas de un catálogo Excel/CSV a texto
     * tabular plano (tipo markdown table) para inyectar al prompt del bot.
     * Formato:
     *   ## Hoja: Productos
     *   | col1 | col2 | col3 |
     *   | v1a  | v1b  | v1c  |
     *   | v2a  | v2b  | v2c  |
     *
     * Si el catálogo tiene muchas filas, las incluye todas hasta ~40K tokens
     * (~160K chars). El límite del prompt se aplica después en getPromptContext.
     */
    @SuppressWarnings("unchecked")
    private String serializeTabularToText(ExcelCatalog cat) {
        try {
            java.util.List<ExcelCatalogRow> rows = rowRepo.findByCatalogIdOrderBySheetNameAscRowIndexAsc(cat.getId());
            if (rows.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            String currentSheet = null;
            java.util.List<String> headers = null;

            for (ExcelCatalogRow row : rows) {
                String sheet = row.getSheetName();
                java.util.Map<String, Object> data;
                try {
                    data = objectMapper.readValue(row.getDataJson(), new TypeReference<java.util.Map<String, Object>>() {});
                } catch (Exception ex) {
                    continue;
                }
                if (data.isEmpty()) continue;

                // Nuevo sheet → escribir header
                if (!java.util.Objects.equals(sheet, currentSheet)) {
                    currentSheet = sheet;
                    headers = new java.util.ArrayList<>(data.keySet());
                    sb.append("\n## Hoja: ").append(sheet).append("\n");
                    sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                    sb.append("|").append("---|".repeat(headers.size())).append("\n");
                }

                // Fila de datos — asegurar orden de headers (aunque a veces vengan valores faltantes)
                StringBuilder rowStr = new StringBuilder("| ");
                for (String h : headers) {
                    Object v = data.get(h);
                    String s = (v == null) ? "" : String.valueOf(v).replace("|", "/").replace("\n", " ");
                    rowStr.append(s).append(" | ");
                }
                sb.append(rowStr).append("\n");

                // Cap de seguridad por si el catálogo es enorme
                if (sb.length() > 160_000) {
                    sb.append("\n*(truncado: el catálogo supera ~40K tokens)*\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[DataSource] error serializando tabular {}: {}", cat.getName(), e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UrlUploadRequest {
        public String name;
        public String description;
        public String url;
        public Integer autoRefreshHours; // null = no refresh
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestUrlRequest {
        public String url;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestUrlResponse {
        public boolean ok;
        public String error;
        public String detectedFilename;
        public String detectedMimeType;
        public Long sizeBytes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataSourceDto {
        public Long id;
        public String name;
        public String description;
        public String originalFilename;
        public Long sizeBytes;
        public Integer totalRows;
        public Boolean active;
        public Instant createdAt;
        public Instant updatedAt;
        public String uploadedBy;
        public String sourceType;
        public String mimeType;
        public String originalUrl;
        public Instant lastRefreshedAt;
        public Integer autoRefreshHours;
        public Integer tokenCount;
        public Boolean hasExtractedText;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptContextResponse {
        public java.util.List<PromptSourceEntry> sources;
        public int totalTokensUsed;
        public int maxTokens;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptSourceEntry {
        public Long id;
        public String name;
        public Integer tokenCount;
        public Boolean included;
        public String text;     // solo si included=true
        public String warning;  // solo si included=false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Migración Fuente → Tabla del bot (Excel/CSV → BotTable)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Devuelve un preview de cómo quedaría la migración: slug propuesto,
     * columnas auto-tipadas con muestras, conteo de filas. NO modifica nada.
     * El admin lo usa para decidir antes de confirmar el migrate.
     */
    @GetMapping("/{id}/migration-preview")
    @Transactional(readOnly = true)
    public DataSourceMigrationService.MigrationPreview migrationPreview(@PathVariable Long id) {
        try {
            return migrationService.preview(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Ejecuta la migración: crea BotTable + records, desactiva la Fuente.
     * Body: { slug?, name?, injectFields? } — los 3 son opcionales (si no
     * se envía slug/name se usan los sugeridos del preview).
     */
    @PostMapping("/{id}/migrate-to-bot-table")
    @Transactional
    public DataSourceMigrationService.MigrationResult migrate(
            @PathVariable Long id,
            @RequestBody(required = false) DataSourceMigrationService.MigrationRequest req) {
        if (req == null) req = new DataSourceMigrationService.MigrationRequest();
        try {
            return migrationService.migrate(id, req);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
