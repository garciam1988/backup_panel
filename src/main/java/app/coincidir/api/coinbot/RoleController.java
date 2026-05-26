package app.coincidir.api.coinbot;

import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.domain.AppRole;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.AppRoleRepository;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RoleController — CRUD de roles dinámicos. Solo accesible para usuarios con
 * permiso {@code canManageRoles} (típicamente DIOS).
 *
 * Endpoints:
 *  - GET    /api/admin/roles
 *  - POST   /api/admin/roles
 *  - PUT    /api/admin/roles/{id}
 *  - DELETE /api/admin/roles/{id}
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final AppRoleRepository roleRepo;
    private final PanelUserRepository userRepo;
    private final PermissionsService permissionsService;
    private final AuditService auditService;

    @GetMapping
    @Transactional(readOnly = true)
    public List<RoleDto> list(Authentication auth) {
        requireCanManageRoles(auth);
        return roleRepo.findAllByOrderByCodeAsc().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public RoleDto create(@RequestBody SaveRoleRequest body, Authentication auth) {
        requireCanManageRoles(auth);
        if (body == null || body.code == null || body.code.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código del rol obligatorio");
        if (body.name == null || body.name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre del rol obligatorio");

        String code = normalizeCode(body.code);
        if (roleRepo.existsByCodeIgnoreCase(code))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un rol con ese código");

        AppRole r = new AppRole();
        r.setCode(code);
        r.setName(body.name.trim());
        r.setDescription(emptyToNull(body.description));
        r.setIsSystem(Boolean.FALSE);
        r.setPermissionsJson(buildJsonFromBody(body));
        AppRole saved = roleRepo.save(r);

        try {
            auditService.logCreate(
                "role.create",
                "Role",
                String.valueOf(saved.getId()),
                saved.getCode() + " — " + saved.getName(),
                "admin",
                snapshotForAudit(saved)
            );
        } catch (Exception e) {
            // No queremos romper la creación del rol si el audit falla, pero sí
            // queremos enterarnos (antes se tragaba en silencio).
            log.warn("[RoleController] falló logCreate de audit para rol id={}: {}",
                    saved.getId(), e.getMessage());
        }

        return toDto(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public RoleDto update(@PathVariable Long id, @RequestBody SaveRoleRequest body, Authentication auth) {
        requireCanManageRoles(auth);
        AppRole r = roleRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado"));

        // Snapshot ANTES de cambios para audit
        Map<String, Object> oldSnap = snapshotForAudit(r);

        // El code de un rol system NO se puede cambiar (DIOS, etc.)
        if (Boolean.TRUE.equals(r.getIsSystem())) {
            if (body.code != null && !normalizeCode(body.code).equals(r.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede cambiar el código de un rol del sistema");
            }
        } else if (body.code != null && !body.code.isBlank()) {
            String newCode = normalizeCode(body.code);
            if (!newCode.equals(r.getCode())) {
                if (roleRepo.existsByCodeIgnoreCase(newCode))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un rol con ese código");
                // También sincronizamos el campo legacy "role" en los usuarios que lo tenían
                List<PanelUser> users = userRepo.findAll().stream()
                        .filter(u -> u.getRoleId() != null && u.getRoleId().equals(r.getId()))
                        .toList();
                for (PanelUser u : users) {
                    u.setRole(newCode);
                }
                userRepo.saveAll(users);
                r.setCode(newCode);
            }
        }

        if (body.name != null && !body.name.isBlank()) r.setName(body.name.trim());
        if (body.description != null) r.setDescription(emptyToNull(body.description));

        // Para rol DIOS forzamos que mantenga fullAccess=true SIEMPRE.
        boolean isDios = Boolean.TRUE.equals(r.getIsSystem())
                && "DIOS".equalsIgnoreCase(r.getCode());

        if (isDios) {
            r.setPermissionsJson(permissionsService.buildPermissionsJson(
                    true, true, true, Collections.emptyList(), Collections.emptyList()
            ));
        } else {
            r.setPermissionsJson(buildJsonFromBody(body));
        }
        AppRole saved = roleRepo.save(r);

        try {
            Map<String, Object> newSnap = snapshotForAudit(saved);
            if (!oldSnap.equals(newSnap)) {
                auditService.logUpdate(
                    "role.update",
                    "Role",
                    String.valueOf(saved.getId()),
                    saved.getCode() + " — " + saved.getName(),
                    "admin",
                    oldSnap,
                    newSnap
                );
            }
        } catch (Exception e) {
            log.warn("[RoleController] falló logUpdate de audit para rol id={}: {}",
                    saved.getId(), e.getMessage());
        }

        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication auth) {
        requireCanManageRoles(auth);
        AppRole r = roleRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado"));
        if (Boolean.TRUE.equals(r.getIsSystem()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede borrar un rol del sistema");

        // Snapshot previo para audit
        Map<String, Object> oldSnap = snapshotForAudit(r);
        String label = r.getCode() + " — " + r.getName();

        // Si hay usuarios asignados al rol, los desvinculamos (roleId=null)
        // para no dejarlos huérfanos. Quedan con rol legacy hasta que el admin
        // les asigne otro rol desde la UI.
        List<PanelUser> attached = userRepo.findAll().stream()
                .filter(u -> u.getRoleId() != null && u.getRoleId().equals(r.getId()))
                .toList();
        for (PanelUser u : attached) {
            u.setRoleId(null);
            // dejamos role legacy intacto para que el front muestre algo coherente
        }
        userRepo.saveAll(attached);

        roleRepo.delete(r);

        try {
            auditService.logDelete(
                "role.delete",
                "Role",
                String.valueOf(id),
                label,
                "admin",
                oldSnap
            );
        } catch (Exception e) {
            log.warn("[RoleController] falló logDelete de audit para rol id={}: {}",
                    id, e.getMessage());
        }
    }

    /**
     * Snapshot del rol para audit. permissionsJson incluido porque cualquier
     * cambio en permisos es info crítica de auditoría (quién dio fullAccess
     * a quién, etc).
     */
    private Map<String, Object> snapshotForAudit(AppRole r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) return m;
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("description", r.getDescription());
        m.put("permissionsJson", r.getPermissionsJson());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void requireCanManageRoles(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String subject = auth.getName();
        EffectivePermissions perms = permissionsService.resolveByUsername(subject);
        if (!perms.canManageRoles()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés permiso para gestionar roles");
        }
    }

    private String buildJsonFromBody(SaveRoleRequest body) {
        boolean fa = body.fullAccess != null && body.fullAccess;
        boolean cmu = body.canManageUsers != null && body.canManageUsers;
        boolean cmr = body.canManageRoles != null && body.canManageRoles;
        List<String> as = body.adminSections == null ? List.of() : body.adminSections;
        List<String> pk = body.panelKeys == null ? List.of() : body.panelKeys;
        return permissionsService.buildPermissionsJson(fa, cmu, cmr, as, pk);
    }

    private static String normalizeCode(String c) {
        return c.trim().toUpperCase().replaceAll("[^A-Z0-9_\\-]", "_");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private RoleDto toDto(AppRole r) {
        RoleDto d = new RoleDto();
        d.id = r.getId();
        d.code = r.getCode();
        d.name = r.getName();
        d.description = r.getDescription();
        d.isSystem = r.getIsSystem();
        d.createdAt = r.getCreatedAt();
        d.updatedAt = r.getUpdatedAt();
        // Decodificamos los permisos a campos planos para que el front no tenga que parsear JSON
        try {
            JsonNode node = permissionsService.mapper().readTree(
                    r.getPermissionsJson() == null ? "{}" : r.getPermissionsJson()
            );
            d.fullAccess     = node.path("fullAccess").asBoolean(false);
            d.canManageUsers = node.path("canManageUsers").asBoolean(false);
            d.canManageRoles = node.path("canManageRoles").asBoolean(false);
            d.adminSections  = readList(node.get("adminSections"));
            d.panelKeys      = readList(node.get("panelKeys"));
        } catch (Exception e) {
            d.adminSections = List.of();
            d.panelKeys = List.of();
        }
        return d;
    }

    private static List<String> readList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            String s = n.asText("");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // DTOs ──────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRoleRequest {
        public String  code;
        public String  name;
        public String  description;
        public Boolean fullAccess;
        public Boolean canManageUsers;
        public Boolean canManageRoles;
        public List<String> adminSections;
        public List<String> panelKeys;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoleDto {
        public Long    id;
        public String  code;
        public String  name;
        public String  description;
        public Boolean isSystem;
        public Boolean fullAccess;
        public Boolean canManageUsers;
        public Boolean canManageRoles;
        public List<String> adminSections;
        public List<String> panelKeys;
        public Instant createdAt;
        public Instant updatedAt;
    }
}
