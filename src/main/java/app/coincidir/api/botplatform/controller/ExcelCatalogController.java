package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRowRepository;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
import app.coincidir.api.tenancy.access.BranchAccessGuard;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.repository.BranchRepository;
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
 *   GET    /api/admin/excel-catalogs             → listado filtrado por scope
 *   GET    /api/admin/excel-catalogs/{id}        → detalle (metadata)
 *   GET    /api/admin/excel-catalogs/{id}/preview?sheet=X&limit=20 → primeras filas
 *   POST   /api/admin/excel-catalogs             → upload (multipart: name, description, branchId?, file)
 *   PUT    /api/admin/excel-catalogs/{id}        → actualizar metadata (name, description, active)
 *   DELETE /api/admin/excel-catalogs/{id}        → eliminar catálogo y todas sus filas
 *
 * Multi-tenancy (Bloque 2):
 *   - Cada catálogo pertenece a una sucursal (branch_id) o es global (NULL).
 *   - Listado: DIOS/ADMIN ven todo; gerente solo ve los de sus branches + globales.
 *   - Lectura individual (GET, preview): se valida acceso vía BranchAccessGuard.
 *   - Escritura (POST upload, PUT, PUT row, DELETE): se valida que el caller
 *     pueda escribir esa branch. Gerentes NO pueden tocar globales.
 *   - En upload, el branchId del nuevo catálogo se resuelve así:
 *       * Si el caller es DIOS/ADMIN y manda branchId en el body → respeta lo que pidió.
 *       * Si el caller es DIOS/ADMIN y NO manda branchId → usa el scope del request
 *         (sucursal elegida en el selector). Sin scope → global (NULL).
 *       * Si el caller es gerente → SIEMPRE se fuerza al scope (su única branch),
 *         ignorando cualquier branchId del body.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/excel-catalogs")
@RequiredArgsConstructor
public class ExcelCatalogController {

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogRowRepository rowRepo;
    private final ExcelCatalogService catalogService;
    private final BranchAccessGuard branchAccess;
    private final BranchRepository branchRepo;
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping
    @Transactional(readOnly = true)
    public List<CatalogDto> list(@RequestParam(value = "all", defaultValue = "true") boolean all,
                                  Authentication auth) {
        String username = auth != null ? auth.getName() : null;

        // Resolución del listado según permisos:
        //   - DIOS / ADMIN (readableBranchIds == null): ven todo. Si tienen
        //     un scope activo, el frontend igual les muestra todo y filtra
        //     visualmente — el backend no oculta data.
        //   - Gerente: solo sus branches + globales. Si no tiene ninguna
        //     branch asignada, solo globales.
        List<Long> readableBranchIds = branchAccess.readableBranchIdsOrNullForUnrestricted(username);

        List<ExcelCatalog> list;
        if (readableBranchIds == null) {
            // DIOS/ADMIN ven todo
            list = all
                    ? catalogRepo.findAllByOrderByNameAsc()
                    : catalogRepo.findByActiveTrueOrderByNameAsc();
        } else if (readableBranchIds.isEmpty()) {
            // Gerente sin branches asignadas — solo globales
            list = all
                    ? catalogRepo.findGlobalsOrderByNameAsc()
                    : catalogRepo.findActiveGlobalsOrderByNameAsc();
        } else {
            list = all
                    ? catalogRepo.findVisibleForBranchesOrderByNameAsc(readableBranchIds)
                    : catalogRepo.findActiveVisibleForBranchesOrderByNameAsc(readableBranchIds);
        }

        // Cargamos los nombres de branch en un solo query batch para evitar N+1
        Map<Long, String> branchNames = loadBranchNames(list);
        return list.stream().map(c -> toDto(c, branchNames)).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<CatalogDto> getOne(@PathVariable Long id, Authentication auth) {
        String username = auth != null ? auth.getName() : null;
        return catalogRepo.findById(id)
                .map(c -> {
                    branchAccess.requireRead(username, c.getBranchId());
                    Map<Long, String> bn = loadBranchNames(List.of(c));
                    return toDto(c, bn);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id,
                                                        @RequestParam(value = "sheet", required = false) String sheet,
                                                        @RequestParam(value = "limit", defaultValue = "20") int limit,
                                                        Authentication auth) {
        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        String username = auth != null ? auth.getName() : null;
        branchAccess.requireRead(username, opt.get().getBranchId());

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
            @RequestParam(value = "branchId", required = false) Long requestedBranchId,
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        String username = auth != null ? auth.getName() : null;
        try {
            BranchScope scope = BranchContext.current();
            Long scopeBranchId = (scope != null) ? scope.getBranchId() : null;

            // El guard decide el branchId final:
            //   - Gerente: forzado a su scope (ignorado lo que pida).
            //   - DIOS/ADMIN: respeta lo pedido, o cae al scope, o queda global.
            Long finalBranchId = branchAccess.resolveBranchForCreate(
                    username, requestedBranchId, scopeBranchId);

            // Validación extra: si tras resolver el branchId quedó != null,
            // verificar que el caller realmente puede escribir esa branch
            // (defensivo — para DIOS/ADMIN siempre pasa, para gerente el
            // resolveBranchForCreate ya forzó a su scope así que también).
            branchAccess.requireWrite(username, finalBranchId);

            String uploadedBy = auth != null ? auth.getName() : "anonymous";
            ExcelCatalog saved = catalogService.uploadCatalog(
                    name, description, finalBranchId, file, uploadedBy);
            Map<Long, String> bn = loadBranchNames(List.of(saved));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved, bn));
        } catch (IllegalArgumentException e) {
            body.put("ok", false); body.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (IllegalStateException e) {
            body.put("ok", false); body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            // Re-lanzar para que Spring serialice el 401/403 correctamente
            throw rse;
        } catch (Exception e) {
            log.error("Error subiendo catálogo", e);
            body.put("ok", false); body.put("error", "Error procesando el archivo: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateMetadata(@PathVariable Long id,
                                             @RequestBody CatalogDto dto,
                                             Authentication auth) {
        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ExcelCatalog e = opt.get();

        String username = auth != null ? auth.getName() : null;
        // El catálogo "vive" en su branch actual — chequeamos write contra esa.
        branchAccess.requireWrite(username, e.getBranchId());

        if (dto.name != null && !dto.name.isBlank()) e.setName(dto.name.trim());
        if (dto.description != null) e.setDescription(dto.description);
        if (dto.active != null) e.setActive(dto.active);

        // Nota: branchId NO es editable desde este endpoint. Decisión de
        // producto (Bloque 2): los catálogos quedan en su sucursal de creación.
        // Mover entre branches se haría con un endpoint dedicado en un futuro.

        ExcelCatalog saved = catalogRepo.save(e);
        Map<Long, String> bn = loadBranchNames(List.of(saved));
        return ResponseEntity.ok(toDto(saved, bn));
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
                                       @RequestBody Map<String, Object> body,
                                       Authentication auth) {
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

        // Validar acceso de escritura sobre el catálogo padre
        ExcelCatalog parent = catalogRepo.findById(catalogId).orElse(null);
        if (parent == null) {
            resp.put("ok", false); resp.put("error", "catalog not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(resp);
        }
        String username = auth != null ? auth.getName() : null;
        branchAccess.requireWrite(username, parent.getBranchId());

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
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        String username = auth != null ? auth.getName() : null;
        branchAccess.requireWrite(username, opt.get().getBranchId());

        catalogService.deleteCatalog(id);
        log.info("excel_catalog eliminado: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carga en batch los nombres de las branches referenciadas por los catálogos
     * del listado. Evita el N+1 que pasaría si hicimos findById por cada DTO.
     */
    private Map<Long, String> loadBranchNames(Collection<ExcelCatalog> catalogs) {
        Set<Long> branchIds = new HashSet<>();
        for (ExcelCatalog c : catalogs) {
            if (c.getBranchId() != null) branchIds.add(c.getBranchId());
        }
        if (branchIds.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        for (Branch b : branchRepo.findAllById(branchIds)) {
            out.put(b.getId(), b.getName());
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CatalogDto {
        public Long      id;
        public String    name;
        public String    description;
        public Long      branchId;
        public String    branchName;  // null = global
        public String    originalFilename;
        public Long      sizeBytes;
        public List<String> sheets;
        public Integer   totalRows;
        public Boolean   active;
        public String    uploadedBy;
        public Instant   createdAt;
        public Instant   updatedAt;
    }

    private CatalogDto toDto(ExcelCatalog e, Map<Long, String> branchNames) {
        CatalogDto d = new CatalogDto();
        d.id               = e.getId();
        d.name             = e.getName();
        d.description      = e.getDescription();
        d.branchId         = e.getBranchId();
        d.branchName       = e.getBranchId() != null ? branchNames.get(e.getBranchId()) : null;
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
