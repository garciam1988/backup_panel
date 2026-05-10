package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.service.ExcelCatalogService;
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
 * Motivación:
 *   El menú digital del CoinBot (DigitalMenu) corre en el navegador del
 *   cliente final, que NO tiene JWT de admin. Si llamamos al endpoint
 *   /api/admin/excel-catalogs/{id}/preview el servidor responde 401.
 *
 *   Para Tablas del bot ya existe /api/public/bot-table-tools/{slug}/records.
 *   Este controller es su equivalente para catálogos Excel/CSV.
 *
 * Seguridad:
 *   Solo se exponen catálogos con active=true. Si el admin desactiva un
 *   catálogo desde el panel, deja de ser consultable públicamente al toque.
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
                log.warn("public preview: fila con data JSON inválido row_id={}", r.getId());
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
}
