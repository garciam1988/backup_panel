package app.coincidir.api.tenancy.controller;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.UserBranchAccess;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * UserBranchAccessAdminController — gestión de qué sucursales tiene
 * asignadas un usuario del admin.
 *
 *   GET  /api/admin/panel-users/{userId}/branches
 *        Lista las branches asignadas al user con el flag isPreferred.
 *
 *   PUT  /api/admin/panel-users/{userId}/branches
 *        REEMPLAZA el set completo de branches del user. Recibe:
 *          {
 *            "branchIds": [3, 5, 7],          // branches a las que tendrá acceso
 *            "preferredBranchId": 5            // null para que el backend elija
 *          }
 *        Las branches no listadas en branchIds se quitan del user.
 *        Si el set queda vacío, el user no podrá entrar al admin (validado al login).
 *
 * Diseño "replace-all" (vs add/remove individual):
 *   La UI del frontend muestra una lista con checkboxes — el admin marca/desmarca
 *   y al guardar manda el set final. Es más simple que mantener add/remove
 *   atómicos y previene races donde dos pestañas ven snapshots distintos.
 *
 * Solo accesible por usuarios con permiso canManageUsers (DIOS y similares).
 *
 * Reglas adicionales:
 *   - Asignar branches a un user DIOS NO tira error pero es no-op: el sistema
 *     ignora user_branch_access para DIOS porque tiene acceso universal.
 *   - Si el preferredBranchId no está en branchIds, devolvemos 400.
 *   - Si alguna branch del set no existe o está inactiva, devolvemos 400.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/panel-users/{userId}/branches")
@RequiredArgsConstructor
public class UserBranchAccessAdminController {

    private final PanelUserRepository panelUserRepo;
    private final BranchRepository branchRepo;
    private final UserBranchAccessRepository userBranchAccessRepo;
    private final PermissionsService permissionsService;

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@PathVariable Long userId, Authentication auth) {
        requireCanManageUsers(auth);
        PanelUser user = panelUserRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));

        List<UserBranchAccess> accesses = userBranchAccessRepo.findByUserId(userId);
        // Cargo todas las branches referenciadas para devolver name/address en
        // un solo response (evita N requests del frontend para enriquecer cada una).
        List<Long> branchIds = accesses.stream().map(UserBranchAccess::getBranchId).toList();
        Map<Long, Branch> branchById = new HashMap<>();
        for (Branch b : branchRepo.findAllById(branchIds)) {
            branchById.put(b.getId(), b);
        }

        List<Map<String, Object>> dtos = new ArrayList<>();
        Long preferredId = null;
        for (UserBranchAccess a : accesses) {
            Branch b = branchById.get(a.getBranchId());
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("branchId", a.getBranchId());
            dto.put("name", b != null ? b.getName() : "?");
            dto.put("slug", b != null ? b.getSlug() : null);
            dto.put("address", b != null ? b.getAddress() : null);
            dto.put("active", b != null ? b.getActive() : false);
            dto.put("isPreferred", Boolean.TRUE.equals(a.getIsPreferred()));
            dtos.add(dto);
            if (Boolean.TRUE.equals(a.getIsPreferred())) preferredId = a.getBranchId();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", userId);
        resp.put("username", user.getUsername());
        resp.put("role", user.getRole());
        resp.put("isDios", "DIOS".equalsIgnoreCase(user.getRole()));
        resp.put("preferredBranchId", preferredId);
        resp.put("branches", dtos);
        return resp;
    }

    @PutMapping
    @Transactional
    public Map<String, Object> replace(@PathVariable Long userId,
                                       @RequestBody ReplaceRequest body,
                                       Authentication auth) {
        requireCanManageUsers(auth);
        PanelUser user = panelUserRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));

        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body requerido");
        }

        // Normalizar: dedupe + remove nulls.
        Set<Long> requested = new LinkedHashSet<>();
        if (body.branchIds != null) {
            for (Long id : body.branchIds) {
                if (id != null && id > 0) requested.add(id);
            }
        }

        // Validar que todas las branches existan y estén activas.
        if (!requested.isEmpty()) {
            List<Branch> found = branchRepo.findAllById(requested);
            if (found.size() != requested.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Una o más sucursales del listado no existen");
            }
            for (Branch b : found) {
                if (!Boolean.TRUE.equals(b.getActive())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "La sucursal '" + b.getName() + "' está inactiva. Activala antes de asignarla.");
                }
            }
        }

        // Validar preferredBranchId: si viene, tiene que estar en requested.
        Long preferredId = body.preferredBranchId;
        if (preferredId != null && !requested.contains(preferredId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sucursal preferida debe estar incluida en el listado de asignadas");
        }
        // Si no vino preferred pero hay branches, marcamos la primera como
        // preferida automáticamente (siempre debe haber una si hay alguna).
        if (preferredId == null && !requested.isEmpty()) {
            preferredId = requested.iterator().next();
        }

        // Estrategia: borrar todas las accesos actuales del user y reinsertar
        // las nuevas. Es atómico dentro de la @Transactional. La tabla es chica
        // (típicamente 1-12 filas por user) así que el delete-then-insert no es
        // problema de performance.
        List<UserBranchAccess> existing = userBranchAccessRepo.findByUserId(userId);
        if (!existing.isEmpty()) {
            userBranchAccessRepo.deleteAll(existing);
            // Flush explícito para que el delete pegue antes del insert siguiente,
            // evita conflictos de unique (user_id, branch_id) en el mismo
            // transaction context.
            userBranchAccessRepo.flush();
        }

        int inserted = 0;
        for (Long bid : requested) {
            UserBranchAccess a = new UserBranchAccess();
            a.setUserId(userId);
            a.setBranchId(bid);
            a.setIsPreferred(bid.equals(preferredId));
            userBranchAccessRepo.save(a);
            inserted++;
        }

        log.info("[UserBranchAccess] user={} → set={} preferred={}",
                user.getUsername(), requested, preferredId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("userId", userId);
        resp.put("assigned", inserted);
        resp.put("preferredBranchId", preferredId);
        if ("DIOS".equalsIgnoreCase(user.getRole())) {
            resp.put("note", "El usuario es DIOS y tiene acceso universal. Estas asignaciones quedan guardadas pero el sistema las ignora.");
        } else if (inserted == 0) {
            resp.put("warning", "El usuario quedó sin sucursales asignadas. No podrá ingresar al admin hasta que se le asigne al menos una.");
        }
        return resp;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void requireCanManageUsers(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        if (!perms.canManageUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Necesitás permiso canManageUsers para gestionar sucursales de usuarios.");
        }
    }

    // ─── DTOs ────────────────────────────────────────────────────────────

    public static class ReplaceRequest {
        public List<Long> branchIds;
        public Long preferredBranchId;
    }
}
