package app.coincidir.api.auth;

import app.coincidir.api.auth.dto.LoginRequest;
import app.coincidir.api.auth.dto.LoginResponse;
import app.coincidir.api.auth.dto.UserMeDto;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.JwtService;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AuthController — login para /admin.
 *
 * SOLO acepta credenciales de PanelUser (sistema unificado de roles dinámicos).
 * Login por username + password. El campo "email" del request se usa como username.
 *
 * Los UserAccount (tabla user_account, sistema legacy de Coincidir/YES Travel)
 * NO pueden entrar a /admin por este controller. Siguen funcionando en sus
 * flujos propios (/api/user/auth, /api/user/profile, /api/coinbot, etc.) sin cambios.
 *
 * El JWT generado incluye:
 *   - sub: username del PanelUser
 *   - role: el role string del usuario (legacy compat)
 *   - uid: id numérico del PanelUser
 *   - userKind: siempre "PANEL_USER"
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PanelUserRepository panelUserRepo;
    private final JwtService jwt;
    private final PermissionsService permissionsService;

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        if (req == null || req.email() == null || req.password() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        String identifier = req.email().trim();
        String pass = req.password();

        // Sólo PanelUser. UserAccount no entra al admin.
        PanelUser pu = panelUserRepo.findByUsername(identifier).orElse(null);
        if (pu == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!Boolean.TRUE.equals(pu.getActive())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario inactivo");
        }
        boolean ok;
        try { ok = BCrypt.checkpw(pass, pu.getPasswordHash()); }
        catch (Exception e) { ok = false; }
        if (!ok) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");

        // Validamos que tenga permiso para entrar a /admin (fullAccess o adminSections)
        EffectivePermissions perms = permissionsService.resolve(pu);
        if (!perms.canEnterAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tu rol no tiene secciones de /admin habilitadas. Pedí al DIOS que te dé acceso.");
        }

        pu.setLastLoginAt(Instant.now());
        panelUserRepo.save(pu);

        String token = jwt.generate(
                pu.getUsername(),
                Map.of(
                        "uid", pu.getId(),
                        "role", pu.getRole() == null ? "" : pu.getRole(),
                        "userKind", "PANEL_USER"
                )
        );
        return new LoginResponse(token);
    }

    /**
     * Devuelve los datos básicos del usuario autenticado.
     * Mantiene el shape viejo (UserMeDto) para no romper código que ya lo consume.
     */
    @GetMapping("/me")
    public UserMeDto me(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String subject = principal.getName();

        PanelUser pu = panelUserRepo.findByUsername(subject).orElse(null);
        if (pu == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }
        return new UserMeDto(
                pu.getId(),
                pu.getUsername(),       // mapeo: el campo "email" del DTO trae username acá
                pu.getRole(),
                pu.getDisplayName(),    // firstName ← displayName
                null,                   // lastName n/a
                pu.getLastLoginAt()
        );
    }

    /**
     * Permisos efectivos del usuario autenticado, en formato consumible por el frontend.
     * Sólo PanelUser. UserAccount obtiene 401 acá (no debería llegar nunca, pero por las dudas).
     */
    @GetMapping("/permissions")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> permissions(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String subject = principal.getName();

        PanelUser pu = panelUserRepo.findByUsername(subject).orElse(null);
        if (pu == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }

        EffectivePermissions p = permissionsService.resolve(pu);
        Map<String, Object> out = new HashMap<>();
        out.put("username", pu.getUsername());
        out.put("displayName", pu.getDisplayName());
        out.put("roleCode", p.roleCode());
        out.put("roleName", p.roleName());
        out.put("fullAccess", p.fullAccess());
        out.put("canManageUsers", p.canManageUsers());
        out.put("canManageRoles", p.canManageRoles());
        out.put("adminSections", p.adminSections());
        out.put("panelKeys", p.panelKeys());
        out.put("userKind", "PANEL_USER");
        out.put("isSystem", Boolean.TRUE.equals(pu.getIsSystem()));
        return out;
    }
}
