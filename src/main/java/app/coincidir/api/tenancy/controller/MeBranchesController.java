package app.coincidir.api.tenancy.controller;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.UserBranchAccess;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.*;

/**
 * MeBranchesController — endpoints del "me" relacionados con tenancy.
 *
 *   GET  /api/me/branches          → lista de branches accesibles para el user
 *                                    autenticado (con flag de cuál es la preferida
 *                                    y/o la activa según JWT).
 *
 *   PUT  /api/me/active-branch     → marca una branch como preferida (persiste
 *                                    en BD para que próximos logins arranquen
 *                                    ahí automáticamente).
 *
 * Diseño:
 *   - DIOS no tiene filas en user_branch_access, pero debe poder ver TODAS las
 *     branches de su marca al consultar /branches. Resolvemos eso preguntando
 *     por el rol del user en cada call.
 *   - Un user no-DIOS solo ve las branches a las que tiene acceso explícito.
 *   - El "active branch" no se guarda en BD para DIOS (porque no tiene fila
 *     en user_branch_access). En su caso, el frontend persiste la selección
 *     en localStorage y la manda como X-Branch-Id en cada request.
 */
@Slf4j
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeBranchesController {

    private final PanelUserRepository panelUserRepo;
    private final BranchRepository branchRepo;
    private final UserBranchAccessRepository userBranchAccessRepo;

    /**
     * Lista las branches accesibles por el user logueado. Si es DIOS, devuelve
     * todas las branches activas (de todas las marcas si hubiera varias). Si no,
     * devuelve solo las branches del user_branch_access.
     *
     * Respuesta:
     *   {
     *     "allBranches": false,                  // true solo para DIOS
     *     "preferredBranchId": 3,                // null si no marcó preferida
     *     "branches": [
     *       { id, brandId, slug, name, address, active, isPreferred }
     *     ]
     *   }
     */
    @GetMapping("/branches")
    @Transactional(readOnly = true)
    public Map<String, Object> branches(Principal principal) {
        PanelUser user = resolveUser(principal);
        boolean isDios = "DIOS".equalsIgnoreCase(user.getRole());

        List<Branch> branches;
        Long preferredBranchId = null;
        Set<Long> preferredSet = new HashSet<>();

        if (isDios) {
            // DIOS ve todas las branches activas. La "preferida" del DIOS no
            // se guarda en BD; el frontend la maneja con localStorage.
            branches = branchRepo.findAll().stream()
                    .filter(b -> Boolean.TRUE.equals(b.getActive()))
                    .toList();
        } else {
            List<UserBranchAccess> accesses = userBranchAccessRepo.findByUserId(user.getId());
            List<Long> ids = new ArrayList<>();
            for (UserBranchAccess a : accesses) {
                ids.add(a.getBranchId());
                if (Boolean.TRUE.equals(a.getIsPreferred())) {
                    preferredBranchId = a.getBranchId();
                    preferredSet.add(a.getBranchId());
                }
            }
            branches = branchRepo.findAllById(ids).stream()
                    .filter(b -> Boolean.TRUE.equals(b.getActive()))
                    .toList();
        }

        List<Map<String, Object>> branchDtos = new ArrayList<>();
        for (Branch b : branches) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", b.getId());
            dto.put("brandId", b.getBrandId());
            dto.put("slug", b.getSlug());
            dto.put("name", b.getName());
            dto.put("address", b.getAddress());
            dto.put("timezone", b.getTimezone());
            dto.put("active", b.getActive());
            dto.put("isPreferred", preferredSet.contains(b.getId()));
            dto.put("isDefault", Boolean.TRUE.equals(b.getDefaultForBrand()));
            branchDtos.add(dto);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allBranches", isDios);
        response.put("preferredBranchId", preferredBranchId);
        response.put("branches", branchDtos);
        return response;
    }

    /**
     * Marca una branch como preferida para el user. Solo aplica a users no-DIOS
     * (DIOS no tiene filas en user_branch_access).
     *
     * Reglas:
     *   - El user debe tener acceso previo a esa branch (entrada existente en
     *     user_branch_access). Si no, 403.
     *   - Marcar una como preferida desmarca cualquier otra del mismo user.
     *
     * Body: { "branchId": 5 }
     */
    @PutMapping("/active-branch")
    @Transactional
    public ResponseEntity<Map<String, Object>> setActiveBranch(@RequestBody Map<String, Object> body,
                                                                Principal principal) {
        PanelUser user = resolveUser(principal);
        if ("DIOS".equalsIgnoreCase(user.getRole())) {
            // DIOS persiste su selección en localStorage, no en BD.
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "DIOS no persiste branch preferida en BD; la selección queda en localStorage del cliente."));
        }

        Long branchId = extractLong(body == null ? null : body.get("branchId"));
        if (branchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branchId requerido");
        }

        // Validar acceso
        Optional<UserBranchAccess> target = userBranchAccessRepo.findByUserIdAndBranchId(user.getId(), branchId);
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés acceso a esa sucursal. Pedile al administrador que te la asigne.");
        }

        // Desmarcar todas las del user y marcar la elegida
        List<UserBranchAccess> all = userBranchAccessRepo.findByUserId(user.getId());
        for (UserBranchAccess a : all) {
            boolean shouldBePref = a.getBranchId().equals(branchId);
            if (!Objects.equals(a.getIsPreferred(), shouldBePref)) {
                a.setIsPreferred(shouldBePref);
                userBranchAccessRepo.save(a);
            }
        }

        log.info("[MeBranches] user={} marcó preferida branch={}", user.getUsername(), branchId);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "preferredBranchId", branchId,
                "note", "Cerrá sesión y volvé a entrar para que el JWT use esta preferencia."));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private PanelUser resolveUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return panelUserRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    private static Long extractLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException nfe) { return null; }
    }
}
