package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRowRepository;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

/**
 * ExcelCatalogController — Upload y gestión de catálogos cargados desde Excel.
 *
 * Endpoints (requieren JWT):
 *   GET    /api/admin/excel-catalogs             → listado
 *   GET    /api/admin/excel-catalogs/{id}        → detalle (metadata)
 *   GET    /api/admin/excel-catalogs/{id}/preview?sheet=X&limit=20 → primeras filas
 *   POST   /api/admin/excel-catalogs             → upload (multipart: name, description, file)
 *   PUT    /api/admin/excel-catalogs/{id}        → actualizar metadata (name, description, active)
 *   DELETE /api/admin/excel-catalogs/{id}        → eliminar catálogo y todas sus filas
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/excel-catalogs")
@RequiredArgsConstructor
public class ExcelCatalogController {

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogRowRepository rowRepo;
    private final ExcelCatalogService catalogService;
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping
    @Transactional(readOnly = true)
    public List<CatalogDto> list(@RequestParam(value = "all", defaultValue = "true") boolean all) {
        List<ExcelCatalog> list = all
                ? catalogRepo.findAllByOrderByNameAsc()
                : catalogRepo.findByActiveTrueOrderByNameAsc();
        return list.stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<CatalogDto> getOne(@PathVariable Long id) {
        return catalogRepo.findById(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id,
                                                        @RequestParam(value = "sheet", required = false) String sheet,
                                                        @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        if (limit < 1) limit = 1;
        if (limit > 500) limit = 500;

        List<ExcelCatalogRow> rows = catalogService.preview(id, sheet, limit);
        List<Map<String, Object>> data = new ArrayList<>();
        for (ExcelCatalogRow r : rows) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = json.readValue(r.getDataJson(), Map.class);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.getId());
                item.put("sheet", r.getSheetName());
                item.put("row", r.getRowIndex());
                item.put("data", dataMap);
                data.add(item);
            } catch (Exception e) {
                log.warn("Fila con data JSON inválido: row_id={}", r.getId());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogId", id);
        body.put("sheetFilter", sheet);
        body.put("limit", limit);
        body.put("count", data.size());
        body.put("rows", data);
        return ResponseEntity.ok(body);
    }

    @PostMapping(consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<?> upload(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String uploadedBy = auth != null ? auth.getName() : "anonymous";
            ExcelCatalog saved = catalogService.uploadCatalog(name, description, file, uploadedBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (IllegalArgumentException e) {
            body.put("ok", false); body.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (IllegalStateException e) {
            body.put("ok", false); body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        } catch (Exception e) {
            log.error("Error subiendo catálogo", e);
            body.put("ok", false); body.put("error", "Error procesando el archivo: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateMetadata(@PathVariable Long id, @RequestBody CatalogDto dto) {
        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ExcelCatalog e = opt.get();
        if (dto.name != null && !dto.name.isBlank()) e.setName(dto.name.trim());
        if (dto.description != null) e.setDescription(dto.description);
        if (dto.active != null) e.setActive(dto.active);
        ExcelCatalog saved = catalogRepo.save(e);
        return ResponseEntity.ok(toDto(saved));
    }

    /**
     * Actualiza los datos de UNA fila del catálogo, mezclando los valores recibidos
     * con los existentes. Permite edición en vivo desde el preview del AdminPanel.
     * El body debe ser un JSON { "data": { "columna": "nuevoValor", ... } }.
     * Solo se pisan las columnas presentes en data; el resto se mantiene.
     */
    @PutMapping("/{catalogId}/rows/{rowId}")
    @Transactional
    public ResponseEntity<?> updateRow(@PathVariable Long catalogId,
                                       @PathVariable Long rowId,
                                       @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<ExcelCatalogRow> opt = rowRepo.findById(rowId);
        if (opt.isEmpty()) {
            resp.put("ok", false); resp.put("error", "row not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(resp);
        }
        ExcelCatalogRow row = opt.get();
        if (!catalogId.equals(row.getCatalogId())) {
            resp.put("ok", false); resp.put("error", "row does not belong to catalog " + catalogId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }

        Object dataObj = body != null ? body.get("data") : null;
        if (!(dataObj instanceof Map)) {
            resp.put("ok", false); resp.put("error", "missing 'data' object in body");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = (Map<String, Object>) dataObj;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = json.readValue(row.getDataJson(), Map.class);
            // Merge conservando orden original y agregando claves nuevas al final.
            Map<String, Object> merged = new LinkedHashMap<>(existing);
            for (Map.Entry<String, Object> en : patch.entrySet()) {
                merged.put(en.getKey(), en.getValue());
            }
            row.setDataJson(json.writeValueAsString(merged));
            rowRepo.save(row);

            resp.put("ok", true);
            resp.put("id", row.getId());
            resp.put("sheet", row.getSheetName());
            resp.put("row", row.getRowIndex());
            resp.put("data", merged);
            log.info("excel_catalog_row actualizada: catalog={} row_id={} keys={}", catalogId, rowId, patch.keySet());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Error actualizando fila {}", rowId, e);
            resp.put("ok", false); resp.put("error", "Error procesando fila: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!catalogRepo.existsById(id)) return ResponseEntity.notFound().build();
        catalogService.deleteCatalog(id);
        log.info("excel_catalog eliminado: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CatalogDto {
        public Long      id;
        public String    name;
        public String    description;
        public String    originalFilename;
        public Long      sizeBytes;
        public List<String> sheets;
        public Integer   totalRows;
        public Boolean   active;
        public String    uploadedBy;
        public Instant   createdAt;
        public Instant   updatedAt;
    }

    private CatalogDto toDto(ExcelCatalog e) {
        CatalogDto d = new CatalogDto();
        d.id               = e.getId();
        d.name             = e.getName();
        d.description      = e.getDescription();
        d.originalFilename = e.getOriginalFilename();
        d.sizeBytes        = e.getSizeBytes();
        d.totalRows        = e.getTotalRows();
        d.active           = e.getActive();
        d.uploadedBy       = e.getUploadedBy();
        d.createdAt        = e.getCreatedAt();
        d.updatedAt        = e.getUpdatedAt();
        if (e.getSheetsJson() != null && !e.getSheetsJson().isBlank()) {
            try {
                d.sheets = json.readValue(e.getSheetsJson(), List.class);
            } catch (Exception ex) { d.sheets = List.of(); }
        }
        return d;
    }
}
