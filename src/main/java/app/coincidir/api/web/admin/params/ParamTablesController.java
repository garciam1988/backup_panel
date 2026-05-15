package app.coincidir.api.web.admin.params;

import app.coincidir.api.web.admin.params.model.ParamTableDef;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/params")
@RequiredArgsConstructor
public class ParamTablesController {

    private final ParamTablesService service;

    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> tables() {
        List<ParamTableDef> defs = service.listTableDefs();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", defs);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{tableKey}")
    public ResponseEntity<?> list(
            @PathVariable String tableKey,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive
    ) {
        try {
            List<Map<String, Object>> rows = service.listRows(tableKey, q, includeInactive);
            return ResponseEntity.ok(Map.of("items", rows));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    /**
     * Opciones para combos (FK) por columna. Pensado principalmente para tablas de relación.
     *
     * Respuesta:
     * {
     *   "columns": {
     *     "id_alojamiento": {"refTable":"alojamientos","refColumn":"id","items":[{"id":1,"label":"...","activo":true}]},
     *     "id_regimen": {"refTable":"regimen","refColumn":"id","items":[...]}
     *   }
     * }
     */
    @GetMapping("/{tableKey}/fk-options")
    public ResponseEntity<?> fkOptions(@PathVariable String tableKey) {
        try {
            return ResponseEntity.ok(service.fkOptions(tableKey));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    @PostMapping("/{tableKey}")
    public ResponseEntity<?> create(@PathVariable String tableKey, @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> created = service.create(tableKey, body);
            return ResponseEntity.ok(created);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    @PutMapping("/{tableKey}/{id}")
    public ResponseEntity<?> update(@PathVariable String tableKey, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> updated = service.update(tableKey, id, body);
            return ResponseEntity.ok(updated);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    @DeleteMapping("/{tableKey}/{id}")
    public ResponseEntity<?> softDelete(@PathVariable String tableKey, @PathVariable String id) {
        try {
            service.softDelete(tableKey, id);
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    @PostMapping("/{tableKey}/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable String tableKey, @PathVariable String id) {
        try {
            service.restore(tableKey, id);
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    @DeleteMapping("/{tableKey}/{id}/hard")
    public ResponseEntity<?> hardDelete(@PathVariable String tableKey, @PathVariable String id) {
        try {
            service.hardDelete(tableKey, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(msg(ex));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg(ex));
        }
    }

    private static String msg(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) m = "Error";
        return m;
    }
}
