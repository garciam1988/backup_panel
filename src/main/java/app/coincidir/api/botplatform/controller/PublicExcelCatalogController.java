package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * PublicExcelCatalogController — preview read-only de catálogos Excel para
 * el CoinBot (chat de cliente final). NO requiere JWT.
 *
 * Endpoints:
 *
 *   GET /api/public/excel-catalogs/{id}/preview         (legacy, por id)
 *   GET /api/public/excel-catalogs/by-name/{name}/preview  (Bloque 4)
 *
 * Bloque 4 — Menú digital filtrado por sucursal:
 *   El admin configura el menú apuntando a un NOMBRE de catálogo (ej: "menu").
 *   En tiempo de request, este controller resuelve ese nombre al catálogo
 *   correcto según la sucursal activa:
 *     1. Si hay X-Branch-Id y existe un catálogo (name, branchId) → usar ese.
 *     2. Si no, intentar fallback al catálogo global (name, branchId=null).
 *     3. Si tampoco → 404.
 *
 *   El BranchContext lo resolvió previamente el {@code BranchResolverFilter}
 *   leyendo el header X-Branch-Id. Endpoints públicos aceptan el header
 *   incluso sin JWT (cobertura del filter para anónimos).
 *
 * Seguridad:
 *   Solo se exponen catálogos con active=true. Si el admin desactiva el
 *   catálogo, deja de ser consultable públicamente al toque.
 *
 * Vive bajo /api/public/** (permitAll global en SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/excel-catalogs")
@RequiredArgsConstructor
public class PublicExcelCatalogController {

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogService catalogService;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Legacy: preview por id absoluto. Se mantiene para configs viejas que
     * persistieron catalogId en menuConfigJson y para callers que no usan
     * el flujo del menú digital (typeo manual, herramientas, etc).
     */
    @GetMapping("/{id}/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> preview(
            @PathVariable Long id,
            @RequestParam(value = "sheet", required = false) String sheet,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {

        Optional<ExcelCatalog> opt = catalogRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        // Solo catálogos activos son públicos.
        ExcelCatalog cat = opt.get();
        if (Boolean.FALSE.equals(cat.getActive())) {
            log.debug("public preview: catalog {} inactivo, no se expone", id);
            return ResponseEntity.notFound().build();
        }

        return buildPreviewResponse(cat, sheet, limit);
    }

    /**
     * Bloque 4 — preview por NOMBRE con resolución automática a branch.
     *
     * El nombre típicamente es "menu" (lo que el admin configura desde
     * MenuSection). El backend resuelve a:
     *   - El catálogo (name, branchActiva) si la branch activa existe y tiene uno.
     *   - El catálogo (name, NULL) (global de marca) como fallback.
     *   - 404 si no hay ninguno.
     *
     * Esto permite que el mismo menuConfig global se traduzca a 11 menús
     * distintos (uno por sucursal) sin duplicar config en el admin.
     */
    @GetMapping("/by-name/{name}/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> previewByName(
            @PathVariable String name,
            @RequestParam(value = "sheet", required = false) String sheet,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name requerido"));
        }
        String cleanName = name.trim();

        // 1) Catálogo de la branch activa, si la hay.
        BranchScope scope = BranchContext.current();
        Long branchId = (scope != null) ? scope.getBranchId() : null;

        ExcelCatalog cat = null;
        if (branchId != null) {
            Optional<ExcelCatalog> opt = catalogRepo.findByNameAndBranchId(cleanName, branchId);
            if (opt.isPresent() && !Boolean.FALSE.equals(opt.get().getActive())) {
                cat = opt.get();
                log.debug("public preview by-name='{}': matched branch={} catalog id={}",
                        cleanName, branchId, cat.getId());
            } else {
                log.debug("public preview by-name='{}': no match para branch={}, fallback a global",
                        cleanName, branchId);
            }
        }

        // 2) Fallback: catálogo global (branchId IS NULL) con ese nombre.
        if (cat == null) {
            Optional<ExcelCatalog> opt = catalogRepo.findByNameAndBranchId(cleanName, null);
            if (opt.isPresent() && !Boolean.FALSE.equals(opt.get().getActive())) {
                cat = opt.get();
                log.debug("public preview by-name='{}': matched global catalog id={}",
                        cleanName, cat.getId());
            }
        }

        // 3) Sin match → 404 con cuerpo informativo (el frontend muestra mensaje).
        if (cat == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Catálogo no encontrado");
            body.put("name", cleanName);
            body.put("branchId", branchId);
            body.put("note", "No existe un catálogo con ese nombre asignado a la sucursal activa ni un fallback global.");
            return ResponseEntity.status(404).body(body);
        }

        return buildPreviewResponse(cat, sheet, limit);
    }

    // ─── helper compartido entre los dos endpoints ───
    private ResponseEntity<Map<String, Object>> buildPreviewResponse(
            ExcelCatalog cat, String sheet, int limit) {
        if (limit < 1) limit = 1;
        if (limit > 500) limit = 500;

        List<ExcelCatalogRow> rows = catalogService.preview(cat.getId(), sheet, limit);
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
                log.warn("public preview: fila con data JSON inválido row_id={}", r.getId());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogId", cat.getId());
        body.put("catalogName", cat.getName());
        // Echo del scope efectivo (útil para debug del frontend):
        //   null   → catálogo global o sin branch activa.
        //   number → branch resuelta para este request.
        body.put("branchId", cat.getBranchId());
        body.put("sheetFilter", sheet);
        body.put("limit", limit);
        body.put("count", data.size());
        body.put("rows", data);
        return ResponseEntity.ok(body);
    }
}
