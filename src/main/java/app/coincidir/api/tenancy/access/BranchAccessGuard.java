package app.coincidir.api.tenancy.access;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BranchAccessGuard — centraliza la lógica de "este user puede ver/editar
 * este recurso branch-scoped?".
 *
 * Lo usan los controllers de catálogos, connectors, y cualquier otro recurso
 * que tenga columna {@code branch_id} con semántica:
 *   - null = global a la marca
 *   - != null = pertenece a esa sucursal
 *
 * Reglas de negocio implementadas (Bloque 2):
 *
 *   LECTURA (canRead)
 *     - DIOS / ADMIN: cualquier branch_id (incluido NULL).
 *     - Gerente: solo recursos cuya branch coincida con alguna de sus branches
 *       asignadas, O recursos globales (branch_id = NULL).
 *
 *   ESCRITURA (canWrite)
 *     - DIOS / ADMIN: cualquier branch_id (incluido NULL → crear/editar globales).
 *     - Gerente: solo recursos cuya branch coincida con alguna de sus branches
 *       asignadas. NO puede tocar globales — son responsabilidad de DIOS/ADMIN.
 *
 *   CREAR (resolveBranchForCreate)
 *     - DIOS / ADMIN: respeta el branchId que mande el caller (null = global, o
 *       cualquier id de branch existente).
 *     - Gerente: SIEMPRE se asigna al scope del request (su branch activa). Si
 *       el caller intentó pasar otro branchId, se ignora (no es escalada, solo
 *       se fuerza al scope; loggeamos warning para visibilidad).
 *
 * Errores:
 *   - 401 si no hay user autenticado en la request.
 *   - 403 si el user existe pero no tiene acceso al recurso.
 *
 * IMPORTANTE: el guard NO resuelve el BranchContext — eso ya lo hizo el
 * {@link app.coincidir.api.tenancy.filter.BranchResolverFilter}. Acá solo
 * validamos contra los datos del user. Para createBranch usamos el scope
 * del context directamente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchAccessGuard {

    private final PanelUserRepository userRepo;
    private final UserBranchAccessRepository accessRepo;
    private final PermissionsService permissionsService;

    /**
     * Devuelve true si el user puede LEER un recurso con esa branchId.
     */
    @Transactional(readOnly = true)
    public boolean canRead(String username, Long resourceBranchId) {
        UserAccessSnapshot snap = snapshot(username);
        if (snap.fullAccessLike) return true;
        // Globales (branchId == null) son legibles por todos los usuarios autenticados.
        if (resourceBranchId == null) return true;
        return snap.branchIds.contains(resourceBranchId);
    }

    /**
     * Devuelve true si el user puede ESCRIBIR (crear/editar/borrar) un recurso
     * con esa branchId.
     */
    @Transactional(readOnly = true)
    public boolean canWrite(String username, Long resourceBranchId) {
        UserAccessSnapshot snap = snapshot(username);
        if (snap.fullAccessLike) return true;
        // Globales NO son escribibles por gerentes — solo DIOS/ADMIN.
        if (resourceBranchId == null) return false;
        return snap.branchIds.contains(resourceBranchId);
    }

    /**
     * Lanza 403 si el user NO puede leer el recurso. No-op si puede.
     */
    public void requireRead(String username, Long resourceBranchId) {
        if (!canRead(username, resourceBranchId)) {
            log.warn("[BranchAccessGuard] User '{}' no puede LEER recurso de branch={}",
                    username, resourceBranchId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés acceso a este recurso (otra sucursal).");
        }
    }

    /**
     * Lanza 403 si el user NO puede escribir el recurso. No-op si puede.
     */
    public void requireWrite(String username, Long resourceBranchId) {
        if (!canWrite(username, resourceBranchId)) {
            log.warn("[BranchAccessGuard] User '{}' no puede ESCRIBIR recurso de branch={}",
                    username, resourceBranchId);
            // Mensaje diferenciado según sea global o de otra branch — ayuda al
            // gerente a entender que el problema es de permisos, no de tenancy.
            String msg = resourceBranchId == null
                    ? "Solo DIOS o ADMIN pueden modificar recursos globales."
                    : "No tenés permiso para modificar recursos de esta sucursal.";
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
        }
    }

    /**
     * Resuelve qué {@code branchId} asignar al crear un nuevo recurso.
     *
     * @param username caller
     * @param requestedBranchId branch que el body del request pidió (o null si
     *                          el caller no mandó nada explícito).
     * @param scopeBranchId branch del BranchContext del request (el header
     *                      X-Branch-Id que el frontend mandó, ya resuelto).
     * @return el branchId final a guardar en el recurso (puede ser null = global).
     * @throws ResponseStatusException 403 si el caller no puede crear nada
     *         en la branch resultante.
     */
    @Transactional(readOnly = true)
    public Long resolveBranchForCreate(String username,
                                       Long requestedBranchId,
                                       Long scopeBranchId) {
        UserAccessSnapshot snap = snapshot(username);

        if (snap.fullAccessLike) {
            // DIOS / ADMIN: respetan lo que pidieron. Si no pidieron nada,
            // caen al scope del request (si hay sucursal elegida) o a global.
            return (requestedBranchId != null) ? requestedBranchId : scopeBranchId;
        }

        // Gerente: SIEMPRE se asigna al scope. Si el caller intentó pasar otro
        // branchId, lo ignoramos (defensivo). El scope lo resolvió el filter
        // a partir de su única/preferida branch del JWT.
        if (scopeBranchId == null) {
            // Caso muy raro: gerente sin scope. Pasaría si el JWT no tiene
            // branches asignadas — no debería poder loguear así, pero por las
            // dudas devolvemos 403 antes que crear un recurso huérfano.
            log.warn("[BranchAccessGuard] User '{}' (no DIOS) intentó crear sin scope — rechazado",
                    username);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tu usuario no tiene sucursal asignada. Pedile a un administrador que te la asigne.");
        }

        // Sanity check: el scope tiene que estar entre sus branches asignadas.
        // (Debería estarlo siempre porque el BranchResolverFilter lo validó al
        // resolverlo, pero defensivo.)
        if (!snap.branchIds.contains(scopeBranchId)) {
            log.warn("[BranchAccessGuard] User '{}' tiene scope={} pero no aparece en sus branches asignadas={}",
                    username, scopeBranchId, snap.branchIds);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Inconsistencia de permisos. Volvé a loguearte e intentá de nuevo.");
        }

        // Si el caller pidió explícitamente otra branch, advertir y forzar al scope.
        if (requestedBranchId != null && !requestedBranchId.equals(scopeBranchId)) {
            log.warn("[BranchAccessGuard] User '{}' pidió crear en branch={} pero su scope es {} — forzado al scope",
                    username, requestedBranchId, scopeBranchId);
        }
        return scopeBranchId;
    }

    /**
     * Devuelve las branches sobre las que el user puede operar. Útil para
     * armar queries de filtrado tipo {@code WHERE branch_id IN (?) OR branch_id IS NULL}.
     *
     * @return null si el user es DIOS/ADMIN (ve todo, no aplica filtro), o la
     *         lista (posiblemente vacía) de branch ids para users no-fullAccess.
     */
    @Transactional(readOnly = true)
    public List<Long> readableBranchIdsOrNullForUnrestricted(String username) {
        UserAccessSnapshot snap = snapshot(username);
        return snap.fullAccessLike ? null : List.copyOf(snap.branchIds);
    }

    // ─── interno ──────────────────────────────────────────────────────────

    private UserAccessSnapshot snapshot(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Autenticación requerida");
        }
        PanelUser user = userRepo.findByUsername(username).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Usuario no encontrado"));

        EffectivePermissions perms = permissionsService.resolve(user);
        // DIOS y ADMIN se tratan igual a nivel acceso a recursos branch-scoped:
        // ambos pueden tocar cualquier sucursal y los globales. La diferencia
        // entre DIOS y ADMIN está en otras secciones (config global del bot,
        // ver PermissionsService#requireDiosOrAdmin), no en catálogos.
        boolean fullAccessLike = perms.fullAccess()
                || "ADMIN".equalsIgnoreCase(perms.roleCode());

        Set<Long> branchIds = new HashSet<>();
        accessRepo.findByUserId(user.getId())
                .forEach(uba -> branchIds.add(uba.getBranchId()));

        return new UserAccessSnapshot(fullAccessLike, branchIds);
    }

    private record UserAccessSnapshot(boolean fullAccessLike, Set<Long> branchIds) {}
}
