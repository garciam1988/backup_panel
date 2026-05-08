package app.coincidir.api.coinbot;

import app.coincidir.api.domain.AppRole;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.AppRoleRepository;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

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
        return toDto(repo.save(u));
    }

    @PutMapping("/{id}")
    @Transactional
    public PanelUserDto update(@PathVariable Long id, @RequestBody SaveRequest body, Authentication auth) {
        requireCanManageUsers(auth);
        PanelUser u = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));

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
        return toDto(repo.save(u));
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
        repo.delete(u);
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
    }
}
