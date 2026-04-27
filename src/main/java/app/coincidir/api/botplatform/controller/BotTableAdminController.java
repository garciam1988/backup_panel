package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.service.BotTableImportExportService;
import app.coincidir.api.botplatform.service.BotTableService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * BotTableAdminController — CRUD desde /admin de las tablas custom del bot
 * y de los registros que contienen.
 *
 *   GET    /api/admin/bot-tables
 *   POST   /api/admin/bot-tables
 *   GET    /api/admin/bot-tables/{id}
 *   PUT    /api/admin/bot-tables/{id}
 *   DELETE /api/admin/bot-tables/{id}     (borra tabla + todos sus registros)
 *
 *   GET    /api/admin/bot-tables/{id}/records   ?page=&size=
 *   POST   /api/admin/bot-tables/{id}/records
 *   PUT    /api/admin/bot-tables/records/{recordId}
 *   DELETE /api/admin/bot-tables/records/{recordId}
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tables")
@RequiredArgsConstructor
public class BotTableAdminController {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final BotTableService service;
    private final BotTableImportExportService ioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────── Tablas ───────

    @GetMapping
    @Transactional(readOnly = true)
    public List<TableDto> list() {
        return tableRepo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TableDto get(@PathVariable Long id) {
        return toDto(tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping
    @Transactional
    public TableDto create(@RequestBody TableSaveRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body vacío");
        if (req.name == null || req.name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name requerido");
        if (req.slug == null || req.slug.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug requerido");
        try { service.validateSlug(req.slug); }
        catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        if (tableRepo.existsBySlug(req.slug))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una tabla con slug '" + req.slug + "'");
        String validatedSchema;
        try { validatedSchema = service.validateSchema(req.columnsJson); }
        catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }

        BotTable t = new BotTable();
        applyTableRequest(t, req);
        t.setColumnsJson(validatedSchema);
        return toDto(tableRepo.save(t));
    }

    @PutMapping("/{id}")
    @Transactional
    public TableDto update(@PathVariable Long id, @RequestBody TableSaveRequest req) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Slug no se puede cambiar (rompe referencias en conversaciones existentes)
        if (req.slug != null && !req.slug.equals(t.getSlug()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El slug no se puede cambiar después de crear la tabla");
        if (req.columnsJson != null) {
            try { t.setColumnsJson(service.validateSchema(req.columnsJson)); }
            catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        }
        applyTableRequest(t, req);
        return toDto(tableRepo.save(t));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        recordRepo.deleteAllByTableId(id);
        tableRepo.deleteById(id);
    }

    // ─────── Registros ───────

    @GetMapping("/{id}/records")
    @Transactional(readOnly = true)
    public RecordsResponse listRecords(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (size <= 0) size = 50;
        if (size > 200) size = 200;
        Page<BotTableRecord> p = recordRepo.findByTableIdOrderByCreatedAtDesc(id, PageRequest.of(page, size));
        RecordsResponse resp = new RecordsResponse();
        resp.items = p.getContent().stream().map(this::toRecordDto).toList();
        resp.total = p.getTotalElements();
        resp.page = page;
        resp.size = size;
        return resp;
    }

    @PostMapping("/{id}/records")
    @Transactional
    public RecordDto createRecord(@PathVariable Long id, @RequestBody RecordSaveRequest req) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            String normalized = service.validateAndNormalizeRecord(t, req.data);
            BotTableRecord rec = new BotTableRecord();
            rec.setTableId(t.getId());
            rec.setDataJson(normalized);
            rec.setSource("admin");
            return toRecordDto(recordRepo.save(rec));
        } catch (BotTableService.SchemaError e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/records/{recordId}")
    @Transactional
    public RecordDto updateRecord(@PathVariable Long recordId, @RequestBody RecordSaveRequest req) {
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BotTable t = tableRepo.findById(rec.getTableId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            // Merge con dataJson actual
            JsonNode current = objectMapper.readTree(rec.getDataJson());
            if (req.data != null && req.data.isObject() && current.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) current).setAll((com.fasterxml.jackson.databind.node.ObjectNode) req.data);
            }
            String normalized = service.validateAndNormalizeRecord(t, current);
            rec.setDataJson(normalized);
            return toRecordDto(recordRepo.save(rec));
        } catch (BotTableService.SchemaError e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/records/{recordId}")
    @Transactional
    public void deleteRecord(@PathVariable Long recordId) {
        recordRepo.deleteById(recordId);
    }

    // ─────── Import / Export ───────

    /** Descarga la tabla como Excel (.xlsx). */
    @GetMapping("/{id}/export/xlsx")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            byte[] bytes = ioService.exportToXlsx(t);
            String filename = sanitizeFilename(t.getName()) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.warn("[Export xlsx] error", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo exportar: " + e.getMessage());
        }
    }

    /** Descarga la tabla como CSV (UTF-8 con BOM). */
    @GetMapping("/{id}/export/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            byte[] bytes = ioService.exportToCsv(t);
            String filename = sanitizeFilename(t.getName()) + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                    .body(bytes);
        } catch (Exception e) {
            log.warn("[Export csv] error", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo exportar: " + e.getMessage());
        }
    }

    /**
     * Importa registros desde un archivo .xlsx o .csv.
     * Param query 'strict=true|false': si true, abortar si una sola fila falla.
     */
    @PostMapping(path = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BotTableImportExportService.ImportReport importFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strict", defaultValue = "false") boolean strict) {
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                return ioService.importFromXlsx(t, bytes, strict);
            } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
                return ioService.importFromCsv(t, bytes, strict);
            } else {
                // Tratamos de inferir del content-type
                String ct = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
                if (ct.contains("spreadsheet") || ct.contains("excel"))
                    return ioService.importFromXlsx(t, bytes, strict);
                if (ct.contains("csv") || ct.contains("text/plain"))
                    return ioService.importFromCsv(t, bytes, strict);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Formato no soportado. Use .xlsx o .csv");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Import] error", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error procesando archivo: " + e.getMessage());
        }
    }

    private static String sanitizeFilename(String s) {
        if (s == null || s.isBlank()) return "tabla";
        return s.replaceAll("[^A-Za-z0-9 _\\-.áéíóúÁÉÍÓÚñÑ]+", "_").trim();
    }

    // ─────── Mapping ───────

    private void applyTableRequest(BotTable t, TableSaveRequest r) {
        if (r.name != null) t.setName(r.name.trim());
        if (r.slug != null && t.getSlug() == null) t.setSlug(r.slug.trim().toLowerCase());
        if (r.description != null) t.setDescription(r.description);
        if (r.active != null) t.setActive(r.active);
        if (r.confirmAdd != null) t.setConfirmAdd(r.confirmAdd);
        if (r.confirmUpdate != null) t.setConfirmUpdate(r.confirmUpdate);
        if (r.confirmDelete != null) t.setConfirmDelete(r.confirmDelete);
        if (r.injectToPrompt != null) t.setInjectToPrompt(r.injectToPrompt);
    }

    private TableDto toDto(BotTable t) {
        TableDto d = new TableDto();
        d.id = t.getId();
        d.name = t.getName();
        d.slug = t.getSlug();
        d.description = t.getDescription();
        d.columnsJson = t.getColumnsJson();
        d.active = t.getActive();
        d.confirmAdd = t.getConfirmAdd();
        d.confirmUpdate = t.getConfirmUpdate();
        d.confirmDelete = t.getConfirmDelete();
        d.injectToPrompt = t.getInjectToPrompt();
        d.recordCount = recordRepo.countByTableId(t.getId());
        d.createdAt = t.getCreatedAt();
        d.updatedAt = t.getUpdatedAt();
        return d;
    }

    private RecordDto toRecordDto(BotTableRecord r) {
        RecordDto d = new RecordDto();
        d.id = r.getId();
        d.tableId = r.getTableId();
        try { d.data = objectMapper.readTree(r.getDataJson()); } catch (Exception ignored) {}
        d.source = r.getSource();
        d.createdAt = r.getCreatedAt();
        d.updatedAt = r.getUpdatedAt();
        return d;
    }

    // ─────── DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableSaveRequest {
        public String name;
        public String slug;
        public String description;
        public String columnsJson;
        public Boolean active;
        public Boolean confirmAdd;
        public Boolean confirmUpdate;
        public Boolean confirmDelete;
        public Boolean injectToPrompt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableDto {
        public Long id;
        public String name;
        public String slug;
        public String description;
        public String columnsJson;
        public Boolean active;
        public Boolean confirmAdd;
        public Boolean confirmUpdate;
        public Boolean confirmDelete;
        public Boolean injectToPrompt;
        public Long recordCount;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordSaveRequest {
        public JsonNode data;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordDto {
        public Long id;
        public Long tableId;
        public JsonNode data;
        public String source;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordsResponse {
        public List<RecordDto> items;
        public Long total;
        public Integer page;
        public Integer size;
    }
}
