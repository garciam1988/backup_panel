package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BranchSmartTablesLayout;
import app.coincidir.api.botplatform.repository.BranchSmartTablesLayoutRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SmartTablesLayoutController — persistencia del layout del salón por
 * sucursal. Reemplaza al esquema anterior basado en localStorage.
 *
 * Endpoints:
 *   GET  /api/admin/smart-tables/layout?branchId=N
 *        → { layoutJson, updatedAt, updatedBy, version } o 404 si no existe.
 *
 *   PUT  /api/admin/smart-tables/layout?branchId=N
 *        body: { layoutJson: "{...}", version?: N }
 *        → idem al GET tras guardar. 409 si version no matchea.
 *
 * Autorización:
 *   - Para LEER: cualquier user autenticado con la sucursal accesible. No
 *     pedimos la sección "smartTables" porque el modo Productivo necesita
 *     leer el layout para mostrarlo, y los operadores entran a /smarttables
 *     solo en ese modo.
 *   - Para ESCRIBIR: el usuario debe tener la sección "smartTables" en
 *     adminSections (o fullAccess / roleCode=ADMIN, que incluyen todo).
 *
 *   La validación de QUE EL USER TENGA ACCESO A LA SUCURSAL la deja al
 *   BranchResolverFilter (común al resto del backend). Si llega un PUT
 *   con un branchId al que el user no tiene acceso, el filter lo rechaza
 *   antes de llegar acá.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/smart-tables")
@RequiredArgsConstructor
public class SmartTablesLayoutController {

    private final BranchSmartTablesLayoutRepository layoutRepo;
    private final PermissionsService permissionsService;

    /** Key de la sección en SECTIONS_MENU. Misma que usa el AdminPanel. */
    private static final String SECTION_KEY = "smartTables";

    @GetMapping("/layout")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getLayout(@RequestParam Long branchId) {
        if (branchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branchId requerido");
        }
        Optional<BranchSmartTablesLayout> opt = layoutRepo.findByBranchId(branchId);
        if (opt.isEmpty()) {
            // 404 es la señal para el frontend de "esta sucursal aún no
            // tiene layout — arrancá con defaults". El frontend lo trata
            // como "estado vacío válido", no como error.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(opt.get()));
    }

    @PutMapping("/layout")
    @Transactional
    public Map<String, Object> putLayout(@RequestParam Long branchId,
                                         @RequestBody PutLayoutRequest req,
                                         Authentication auth) {
        if (branchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branchId requerido");
        }
        if (req == null || req.layoutJson == null || req.layoutJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layoutJson requerido");
        }
        requireCanEditLayout(auth);

        // Hard cap defensivo. Layouts típicos rondan los 10-50 KB.
        // 2 MB es un margen amplísimo que igual previene abusos.
        if (req.layoutJson.length() > 2 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "layoutJson demasiado grande (>2 MB)");
        }

        Optional<BranchSmartTablesLayout> existing = layoutRepo.findByBranchId(branchId);
        BranchSmartTablesLayout row;
        if (existing.isPresent()) {
            row = existing.get();
            // Optimistic locking: si el cliente mandó una version y no
            // coincide, otro ya guardó. Si no mandó, asumimos "force"
            // (caller viejo, no chequeamos).
            if (req.version != null && !req.version.equals(row.getVersion())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Otro usuario actualizó el layout. Refrescá y volvé a intentar. " +
                        "(version esperada=" + row.getVersion() + ", recibida=" + req.version + ")");
            }
            row.setLayoutJson(req.layoutJson);
            row.setUpdatedBy(auth != null ? auth.getName() : null);
            row.setVersion(row.getVersion() + 1);
        } else {
            row = new BranchSmartTablesLayout();
            row.setBranchId(branchId);
            row.setLayoutJson(req.layoutJson);
            row.setUpdatedBy(auth != null ? auth.getName() : null);
            row.setVersion(1L);
        }
        row = layoutRepo.save(row);
        return toDto(row);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Verifica que el usuario tenga permiso para escribir el layout.
     * Regla: fullAccess (DIOS) o roleCode = ADMIN o sección "smartTables"
     * en adminSections.
     */
    private void requireCanEditLayout(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        if (perms.fullAccess()) return;
        String role = perms.roleCode();
        if (role != null && "ADMIN".equalsIgnoreCase(role)) return;
        if (perms.hasAdminSection(SECTION_KEY)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tu rol no tiene permiso para editar el layout de Smart Tables. " +
                "Pedile al administrador que habilite la sección '" + SECTION_KEY + "' para tu rol.");
    }

    private static Map<String, Object> toDto(BranchSmartTablesLayout row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branchId", row.getBranchId());
        m.put("layoutJson", row.getLayoutJson());
        m.put("version", row.getVersion());
        m.put("updatedBy", row.getUpdatedBy());
        Instant ua = row.getUpdatedAt();
        m.put("updatedAt", ua != null ? ua.toString() : null);
        return m;
    }

    public static class PutLayoutRequest {
        public String layoutJson;
        /** Version esperada — para optimistic locking. Opcional. */
        public Long version;
    }
}
