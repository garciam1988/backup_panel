package app.coincidir.api.security;

import app.coincidir.api.domain.AppRole;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.AppRoleRepository;
import app.coincidir.api.repository.PanelUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * PermissionsService — calcula los permisos efectivos de un usuario.
 *
 * Reglas de combinación (rol + override del usuario):
 *  - Si el rol tiene {@code fullAccess=true} (DIOS), el usuario tiene acceso TOTAL
 *    sin importar overrides. Para todos los efectos prácticos, ve y opera todo.
 *  - {@code adminSections} del usuario (CSV) sobreescribe al del rol si está seteado.
 *    Si no está seteado, gana el del rol.
 *  - {@code panelKeys} del usuario (CSV) sobreescribe al del rol si está seteado.
 *  - Los flags booleanos ({@code canManageUsers}, {@code canManageRoles}) vienen del rol.
 *
 * Si el usuario no tiene rol asignado, caemos a valores por defecto basados en el
 * campo legacy {@code role} (OPERATOR ve solo /panel, PANEL_ADMIN igual + gestiona usuarios).
 * Esto preserva compatibilidad con usuarios viejos creados antes de esta feature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionsService {

    private final AppRoleRepository roleRepo;
    private final PanelUserRepository userRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Snapshot inmutable de los permisos efectivos. */
    public record EffectivePermissions(
            String roleCode,
            String roleName,
            boolean fullAccess,
            boolean canManageUsers,
            boolean canManageRoles,
            List<String> adminSections,   // null o vacío = sin acceso a /admin (salvo fullAccess)
            List<String> panelKeys         // null o vacío = sin acceso a /panel (salvo fullAccess)
    ) {
        /** ¿Puede entrar a /admin? */
        public boolean canEnterAdmin() {
            return fullAccess || (adminSections != null && !adminSections.isEmpty());
        }

        /** ¿Puede ver/operar una sección particular del AdminPanel? */
        public boolean hasAdminSection(String key) {
            if (fullAccess) return true;
            if (adminSections == null) return false;
            return adminSections.contains(key);
        }

        /** ¿Puede ver/operar un panel particular en /panel? */
        public boolean hasPanelKey(String key) {
            if (fullAccess) return true;
            if (panelKeys == null) return false;
            return panelKeys.contains(key);
        }
    }

    /** Resuelve los permisos efectivos para un usuario dado. */
    public EffectivePermissions resolve(PanelUser user) {
        if (user == null) {
            return new EffectivePermissions(null, null, false, false, false, List.of(), List.of());
        }

        // 1) Cargar rol si está asignado
        AppRole role = null;
        if (user.getRoleId() != null) {
            role = roleRepo.findById(user.getRoleId()).orElse(null);
        }

        // 2) Parsear permisos del rol (con defaults seguros)
        boolean fullAccess     = false;
        boolean canManageUsers = false;
        boolean canManageRoles = false;
        List<String> roleAdminSections = null;
        List<String> rolePanelKeys = null;
        String roleCode = null;
        String roleName = null;

        if (role != null) {
            roleCode = role.getCode();
            roleName = role.getName();
            JsonNode perms = parseJson(role.getPermissionsJson());
            if (perms != null) {
                fullAccess     = perms.path("fullAccess").asBoolean(false);
                canManageUsers = perms.path("canManageUsers").asBoolean(false);
                canManageRoles = perms.path("canManageRoles").asBoolean(false);
                roleAdminSections = readStringList(perms.get("adminSections"));
                rolePanelKeys     = readStringList(perms.get("panelKeys"));
            }
        } else {
            // Sin rol: caemos a defaults legacy basados en user.getRole()
            String legacy = user.getRole() == null ? "" : user.getRole().toUpperCase();
            roleCode = legacy.isBlank() ? "OPERATOR" : legacy;
            roleName = roleCode;
            switch (roleCode) {
                case "DIOS" -> {
                    fullAccess = true;
                    canManageUsers = true;
                    canManageRoles = true;
                }
                case "ADMIN" -> {
                    canManageUsers = true;
                    // Sin acceso a /admin específico hasta que tenga adminSections asignadas
                }
                case "PANEL_ADMIN" -> {
                    canManageUsers = true;
                }
                default -> { /* OPERATOR — sin permisos extra */ }
            }
        }

        // 3) Override por usuario (si está seteado, gana sobre el rol)
        List<String> finalAdminSections = csvToList(user.getEnabledAdminSections());
        if (finalAdminSections == null) finalAdminSections = roleAdminSections;
        if (finalAdminSections == null) finalAdminSections = List.of();

        List<String> finalPanelKeys = csvToList(user.getEnabledPanels());
        if (finalPanelKeys == null) finalPanelKeys = rolePanelKeys;
        if (finalPanelKeys == null) finalPanelKeys = List.of();

        return new EffectivePermissions(
                roleCode,
                roleName,
                fullAccess,
                canManageUsers,
                canManageRoles,
                finalAdminSections,
                finalPanelKeys
        );
    }

    /** Versión por username, lookup automático. Devuelve permisos vacíos si no existe. */
    public EffectivePermissions resolveByUsername(String username) {
        if (username == null) return resolve(null);
        return userRepo.findByUsername(username).map(this::resolve).orElseGet(() -> resolve(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers de serialización
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construye un JSON canónico de permisos (útil para seeds y endpoints que
     * acepten estructura suelta). Acepta valores nulos/vacíos sin estallar.
     */
    public String buildPermissionsJson(
            boolean fullAccess,
            boolean canManageUsers,
            boolean canManageRoles,
            Collection<String> adminSections,
            Collection<String> panelKeys
    ) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("fullAccess", fullAccess);
        obj.put("canManageUsers", canManageUsers);
        obj.put("canManageRoles", canManageRoles);
        ArrayNode adm = obj.putArray("adminSections");
        if (adminSections != null) adminSections.stream().filter(Objects::nonNull).distinct().forEach(adm::add);
        ArrayNode pn = obj.putArray("panelKeys");
        if (panelKeys != null) panelKeys.stream().filter(Objects::nonNull).distinct().forEach(pn::add);
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("No se pudo serializar permissionsJson", e);
            return "{}";
        }
    }

    /** Permite a otros componentes leer un permissionsJson sin parsearlo a mano. */
    public ObjectMapper mapper() { return mapper; }

    private JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try { return mapper.readTree(s); }
        catch (Exception e) {
            log.warn("permissionsJson inválido: {}", e.getMessage());
            return null;
        }
    }

    private List<String> readStringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return null;
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out; // puede estar vacía → "lista vacía explícita = sin permisos"
    }

    /**
     * Convierte un CSV a lista. Devuelve null si el CSV es null o blank
     * (eso significa "no override, usar el rol"). Devuelve lista vacía solo
     * si el usuario explícitamente puso un string sin items útiles.
     */
    private List<String> csvToList(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
