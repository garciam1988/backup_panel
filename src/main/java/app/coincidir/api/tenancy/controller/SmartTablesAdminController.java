package app.coincidir.api.tenancy.controller;

import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartTablesAdminController — endpoint específico para activar/desactivar el
 * módulo Smart Tables en cada sucursal.
 *
 * Por qué un controller aparte (no extendemos TenancyController):
 *   - El PUT /api/admin/tenancy/branches/{id} de TenancyController exige
 *     `canManageUsers` (típicamente solo DIOS) y modifica varios campos del
 *     branch a la vez (slug, address, timezone, etc). Eso es demasiado
 *     amplio para el caso "ADMIN quiere prender/apagar Smart Tables".
 *   - Smart Tables se considera una feature flag por sucursal. Si en el
 *     futuro aparecen más features con el mismo patrón (delivery on/off,
 *     loyalty on/off, etc), conviene tener controllers chicos por feature
 *     que un mega-controller con todos los toggles.
 *
 * Autorización:
 *   - Solo {@code fullAccess} (DIOS) o {@code roleCode} = "ADMIN" pueden
 *     modificar el flag. El resto recibe 403.
 *   - Esta política es más estricta que la sección "smartTables" del
 *     SECTIONS_MENU del AdminPanel (que sí podría asignarse a roles
 *     operativos): acá pedimos rol de administración del tenant, no
 *     acceso a la pantalla.
 *
 * Endpoint:
 *   PUT /api/admin/tenancy/branches/{id}/smart-tables
 *   Body: { "enabled": true | false }
 *   Resp: { id, name, slug, smartTablesEnabled }
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tenancy/branches")
@RequiredArgsConstructor
public class SmartTablesAdminController {

    private final BranchRepository branchRepo;
    private final PermissionsService permissionsService;

    @PutMapping("/{id}/smart-tables")
    @Transactional
    public Map<String, Object> setSmartTablesEnabled(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body,
                                                     Authentication auth) {
        requireAdminOrDios(auth);

        if (body == null || body.get("enabled") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Campo 'enabled' (boolean) requerido en el body.");
        }
        Object raw = body.get("enabled");
        boolean enabled;
        if (raw instanceof Boolean b) {
            enabled = b;
        } else {
            // Tolerar "true"/"false" como string también — algunos clientes
            // envían strings cuando vienen de inputs HTML.
            enabled = Boolean.parseBoolean(String.valueOf(raw));
        }

        Branch branch = branchRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sucursal no encontrada"));

        // Idempotente: si ya está en el estado pedido, no hacemos UPDATE
        // (evita disparar @PreUpdate y bumpear updatedAt sin razón).
        boolean current = Boolean.TRUE.equals(branch.getSmartTablesEnabled());
        if (current != enabled) {
            branch.setSmartTablesEnabled(enabled);
            branchRepo.save(branch);
            log.info("[SmartTables] Sucursal id={} '{}' → smartTablesEnabled={} por user='{}'",
                    id, branch.getName(), enabled, auth.getName());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", branch.getId());
        resp.put("name", branch.getName());
        resp.put("slug", branch.getSlug());
        resp.put("smartTablesEnabled", Boolean.TRUE.equals(branch.getSmartTablesEnabled()));
        return resp;
    }

    /**
     * Solo ADMIN y DIOS pueden tocar este flag. El resto, incluso si tiene
     * la sección "smartTables" habilitada en su rol, no puede activarla
     * en una sucursal — solo usarla cuando ya está activa.
     */
    private void requireAdminOrDios(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        if (perms.fullAccess()) return;
        String role = perms.roleCode();
        if (role != null && "ADMIN".equalsIgnoreCase(role)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Solo ADMIN o DIOS pueden activar o desactivar Smart Tables en una sucursal.");
    }
}
