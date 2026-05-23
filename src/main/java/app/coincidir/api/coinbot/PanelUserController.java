package app.coincidir.api.coinbot;

import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.domain.AppRole;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.AppRoleRepository;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.UserBranchAccess;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PanelUserController — CRUD de usuarios del sistema (operadores y admins).
 *
 * Endpoints (mantenemos el path viejo /api/admin/panel-users por compat):
 *   GET    /api/admin/panel-users
 *   POST   /api/admin/panel-users
 *   PUT    /api/admin/panel-users/{id}
 *   DELETE /api/admin/panel-users/{id}
 *
 * Soporta el nuevo modelo de roles dinámicos:
 *   - {@code roleId}: FK a AppRole (fuente de verdad de permisos)
 *   - {@code role}:   string legacy, se mantiene en sync con role.code
 *   - {@code enabledAdminSections}: override CSV para secciones de /admin
 *   - {@code enabledPanels}: override CSV para paneles de /panel (legacy, sigue igual)
 *
 * Solo accesible para usuarios con permiso {@code canManageUsers}.
 */
@RestController
@RequestMapping("/api/admin/panel-users")
@RequiredArgsConstructor
public class PanelUserController {

    private final PanelUserRepository repo;
    private final AppRoleRepository roleRepo;
    private final PermissionsService permissionsService;
    private final AuditService auditService;
    private final UserBranchAccessRepository userBranchAccessRepo;
    private final BranchRepository branchRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public List<PanelUserDto> list(Authentication auth) {
        requireCanManageUsers(auth);
        return repo.findAllByOrderByUsernameAsc().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public PanelUserDto create(@RequestBody SaveRequest body, Authentication auth) {
        requireCanManageUsers(auth);
        if (body.username == null || body.username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username obligatorio");
        if (body.password == null || body.password.length() < 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password mínimo 4 caracteres");

        String username = body.username.trim();
        if (repo.existsByUsername(username))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe ese username");

        // Resolver rol asignado (si se envió roleId)
        AppRole role = resolveRole(body.roleId);

        PanelUser u = new PanelUser();
        u.setUsername(username);
        u.setDisplayName(emptyToNull(body.displayName));
        u.setPasswordHash(BCrypt.hashpw(body.password, BCrypt.gensalt()));
        u.setRoleId(role == null ? null : role.getId());
        u.setRole(resolveLegacyRole(role, body.role));
        u.setEnabledPanels(emptyToNull(body.enabledPanels));
        u.setEnabledAdminSections(emptyToNull(body.enabledAdminSections));
        u.setActive(body.active == null ? Boolean.TRUE : body.active);
        u.setIsSystem(Boolean.FALSE);
        if (auth != null && auth.getName() != null) u.setCreatedBy(auth.getName());
        PanelUser saved = repo.save(u);

        try {
            auditService.logCreate(
                "user.create",
                "User",
                String.valueOf(saved.getId()),
                saved.getUsername(),
                "admin",
                snapshotForAudit(saved)
            );
        } catch (Exception ignored) {}

        return toDto(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public PanelUserDto update(@PathVariable Long id, @RequestBody SaveRequest body, Authentication auth) {
        requireCanManageUsers(auth);
        PanelUser u = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Snapshot ANTES de aplicar cambios — para que el diff sea preciso
        Map<String, Object> oldSnap = snapshotForAudit(u);

        // Sólo DIOS (fullAccess) puede editar usuarios system
        if (Boolean.TRUE.equals(u.getIsSystem())) {
            EffectivePermissions perms = resolveCallerPermissions(auth);
            if (!perms.fullAccess()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Solo DIOS puede editar usuarios del sistema");
            }
            // Y aún así, no se puede desactivar al DIOS ni cambiarle el rol
            if (body.active != null && !body.active) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede desactivar un usuario del sistema");
            }
            if (body.roleId != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede cambiar el rol de un usuario del sistema");
            }
            // No se permite cambio de password desde la UI (sólo por env vars)
            if (body.password != null && !body.password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La contraseña de DIOS sólo se cambia por variables de entorno");
            }
        }

        if (body.displayName != null) u.setDisplayName(emptyToNull(body.displayName));

        // Cambio de rol (asignación)
        if (body.roleId != null && !Boolean.TRUE.equals(u.getIsSystem())) {
            AppRole role = resolveRole(body.roleId);
            u.setRoleId(role == null ? null : role.getId());
            u.setRole(resolveLegacyRole(role, body.role));
        } else if (body.role != null && !Boolean.TRUE.equals(u.getIsSystem())) {
            // Permite seteo legacy puro (sin roleId) para back-compat
            u.setRole(body.role.trim().toUpperCase());
        }

        if (body.enabledPanels != null)        u.setEnabledPanels(emptyToNull(body.enabledPanels));
        if (body.enabledAdminSections != null) u.setEnabledAdminSections(emptyToNull(body.enabledAdminSections));
        if (body.active != null && !Boolean.TRUE.equals(u.getIsSystem())) u.setActive(body.active);

        if (body.password != null && !body.password.isBlank() && !Boolean.TRUE.equals(u.getIsSystem())) {
            if (body.password.length() < 4)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password mínimo 4 caracteres");
            u.setPasswordHash(BCrypt.hashpw(body.password, BCrypt.gensalt()));
        }
        PanelUser saved = repo.save(u);

        try {
            Map<String, Object> newSnap = snapshotForAudit(saved);
            if (!oldSnap.equals(newSnap)) {
                auditService.logUpdate(
                    "user.update",
                    "User",
                    String.valueOf(saved.getId()),
                    saved.getUsername(),
                    "admin",
                    oldSnap,
                    newSnap
                );
            }
        } catch (Exception ignored) {}

        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication auth) {
        requireCanManageUsers(auth);
        PanelUser u = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Boolean.TRUE.equals(u.getIsSystem())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede borrar un usuario del sistema (DIOS)");
        }
        Map<String, Object> oldSnap = snapshotForAudit(u);
        String username = u.getUsername();

        // Borramos PRIMERO los accesos a sucursales. Si no, quedan filas
        // huérfanas en `user_branch_access` apuntando a un `user_id` que ya
        // no existe — y eso después rompe el flujo de "borrar sucursal" del
        // panel de tenancy: el check de `findByBranchId` cuenta esas filas
        // huérfanas como "usuarios asignados" e impide borrar la branch
        // diciendo "tiene N usuario(s) asignado(s)" cuando en realidad no
        // hay ninguno vivo. Bug observado al intentar borrar Colegiales
        // después de haber borrado al único user asignado.
        userBranchAccessRepo.deleteByUserId(id);

        repo.delete(u);

        try {
            auditService.logDelete(
                "user.delete",
                "User",
                String.valueOf(id),
                username,
                "admin",
                oldSnap
            );
        } catch (Exception ignored) {}
    }

    /**
     * Snapshot de campos auditables del usuario. Importante: NO incluimos
     * passwordHash. El AuditEventListener tiene un filtro de campos sensibles
     * que igual lo rechazaría, pero por defensa en profundidad lo omitimos.
     *
     * Sí incluimos los flags que marcan cambios de permisos importantes:
     * role, roleId, enabledAdminSections, enabledPanels, active.
     */
    private Map<String, Object> snapshotForAudit(PanelUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (u == null) return m;
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("roleId", u.getRoleId());
        m.put("enabledAdminSections", u.getEnabledAdminSections());
        m.put("enabledPanels", u.getEnabledPanels());
        m.put("active", u.getActive());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void requireCanManageUsers(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String subject = auth.getName();
        EffectivePermissions perms = permissionsService.resolveByUsername(subject);
        if (!perms.canManageUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés permiso para gestionar usuarios");
        }
    }

    private EffectivePermissions resolveCallerPermissions(Authentication auth) {
        if (auth == null || auth.getName() == null) return permissionsService.resolveByUsername("");
        return permissionsService.resolveByUsername(auth.getName());
    }

    private AppRole resolveRole(Long roleId) {
        if (roleId == null) return null;
        return roleRepo.findById(roleId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rol no encontrado"));
    }

    /**
     * Determina el campo legacy {@code role} del PanelUser.
     * Si hay rol asignado, gana su {@code code}. Si no, usa el legacy del body
     * o cae a "OPERATOR".
     */
    private String resolveLegacyRole(AppRole role, String bodyRoleLegacy) {
        if (role != null) return role.getCode();
        if (bodyRoleLegacy != null && !bodyRoleLegacy.isBlank()) return bodyRoleLegacy.trim().toUpperCase();
        return "OPERATOR";
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private PanelUserDto toDto(PanelUser u) {
        PanelUserDto d = new PanelUserDto();
        d.id            = u.getId();
        d.username      = u.getUsername();
        d.displayName   = u.getDisplayName();
        d.role          = u.getRole();
        d.roleId        = u.getRoleId();
        d.enabledPanels = u.getEnabledPanels();
        d.enabledAdminSections = u.getEnabledAdminSections();
        d.active        = u.getActive();
        d.isSystem      = u.getIsSystem();
        d.lastLoginAt   = u.getLastLoginAt();
        d.createdAt     = u.getCreatedAt();
        d.createdBy     = u.getCreatedBy();
        // Resolvemos roleCode/roleName desde la FK para mostrarlos en la tabla
        if (u.getRoleId() != null) {
            roleRepo.findById(u.getRoleId()).ifPresent(r -> {
                d.roleCode = r.getCode();
                d.roleName = r.getName();
            });
        }
        // ── Sucursales asignadas ────────────────────────────────────────
        // Para roles cross-branch (DIOS y cualquier rol con fullAccess=true,
        // típicamente un "Administrador" custom) no tiene sentido listar
        // sucursales — esos usuarios ven todas las marcas. Si por alguna
        // razón quedaron filas de user_branch_access de cuando ese user no
        // era cross-branch, las ignoramos.
        //
        // Usamos PermissionsService.resolve para no replicar la lógica de
        // parseo del permissions_json (que parsea el flag fullAccess desde
        // un JSON embebido en el rol).
        //
        // Para usuarios branch-scoped (Gerente, Encargado, Caja, etc.),
        // listamos las branches a las que tienen acceso, marcando la
        // preferida primero (para que el frontend pueda renderear
        // "📍 Preferida (+N más)" sin tener que ordenar).
        //
        // Performance: este endpoint se llama una sola vez al abrir el tab
        // de Usuarios y la lista típica son 20-50 users. Las queries por
        // userId van por índice. Si crece a centenares, conviene migrar a
        // un solo query con JOIN, pero por ahora N+1 es aceptable.
        d.branches = new ArrayList<>();
        EffectivePermissions perms = permissionsService.resolve(u);
        boolean isCrossBranch = (perms != null && perms.fullAccess())
                             || Boolean.TRUE.equals(u.getIsSystem());
        if (!isCrossBranch) {
            List<UserBranchAccess> accesses = userBranchAccessRepo.findByUserId(u.getId());
            if (!accesses.isEmpty()) {
                // Cargamos las branches en un solo query
                List<Long> branchIds = accesses.stream().map(UserBranchAccess::getBranchId).toList();
                Map<Long, Branch> branchById = new LinkedHashMap<>();
                branchRepo.findAllById(branchIds).forEach(b -> branchById.put(b.getId(), b));

                // Ordenamos: preferida primero, después por nombre
                accesses.stream()
                    .sorted(Comparator
                        .comparing((UserBranchAccess a) -> !Boolean.TRUE.equals(a.getIsPreferred()))
                        .thenComparing(a -> {
                            Branch b = branchById.get(a.getBranchId());
                            return b != null && b.getName() != null ? b.getName() : "";
                        }))
                    .forEach(a -> {
                        Branch b = branchById.get(a.getBranchId());
                        if (b == null) return; // branch huérfana — la branch fue borrada y user_branch_access quedó vivo
                        UserBranchDto bd = new UserBranchDto();
                        bd.id = b.getId();
                        bd.name = b.getName();
                        bd.isPreferred = Boolean.TRUE.equals(a.getIsPreferred());
                        d.branches.add(bd);
                    });
            }
        }
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public String  username;
        public String  displayName;
        public String  password;             // opcional en update
        public String  role;                 // legacy, opcional
        public Long    roleId;               // nuevo: FK a AppRole
        public String  enabledPanels;        // CSV (legacy)
        public String  enabledAdminSections; // CSV nuevo
        public Boolean active;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PanelUserDto {
        public Long    id;
        public String  username;
        public String  displayName;
        public String  role;
        public Long    roleId;
        public String  roleCode;             // resolved
        public String  roleName;             // resolved
        public String  enabledPanels;
        public String  enabledAdminSections;
        public Boolean active;
        public Boolean isSystem;
        public Instant lastLoginAt;
        public Instant createdAt;
        public String  createdBy;
        /**
         * Sucursales a las que el usuario tiene acceso. Lista vacía si no
         * tiene asignadas o si es un rol cross-branch (DIOS / sistema).
         * La preferida — si la hay — va PRIMERO en la lista; el resto va
         * ordenado por nombre.
         */
        public List<UserBranchDto> branches;
    }

    /**
     * Sub-DTO mínimo de sucursal embebido en PanelUserDto. Solo carga lo
     * necesario para el listado en /admin (id + nombre + flag de preferida)
     * — para el resto de los datos de la branch el frontend usa el endpoint
     * dedicado de /api/admin/branches.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserBranchDto {
        public Long    id;
        public String  name;
        public Boolean isPreferred;
    }
}
