package app.coincidir.api.panel;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PanelAuthController — login de usuarios del /panel.
 *
 * Devuelve, además del token y datos básicos, los permisos efectivos para
 * que el front filtre la lista de paneles. Mantiene el contrato viejo
 * (sigue devolviendo {@code enabledPanels} CSV) PERO también suma:
 *  - {@code panelKeys}  → lista efectiva (override usuario o herencia rol)
 *  - {@code fullAccess} → DIOS bypassea filtros
 */
@Slf4j
@RestController
@RequestMapping("/api/panel/auth")
public class PanelAuthController {

    private final PanelUserRepository repo;
    private final PermissionsService permissionsService;

    private final String base64Secret;
    private final long ttlSeconds;

    private SecretKey signingKey;
    private long ttlMillis;

    public PanelAuthController(
            PanelUserRepository repo,
            PermissionsService permissionsService,
            @Value("${security.jwt.secret:${jwt.secret:8k8L0p0vC9k14y3w3m1k+T1bJ3Vq1Q9WZzqQyvV6f1s8=}}") String base64Secret,
            @Value("${security.jwt.ttl-seconds:${jwt.ttlSeconds:43200}}") long ttlSeconds
    ) {
        this.repo = repo;
        this.permissionsService = permissionsService;
        this.base64Secret = base64Secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.ttlMillis = ttlSeconds * 1000L;
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || req.username == null || req.password == null) {
            return ResponseEntity.status(401).build();
        }
        PanelUser u = repo.findByUsername(req.username.trim()).orElse(null);
        if (u == null || !Boolean.TRUE.equals(u.getActive())) {
            return ResponseEntity.status(401).build();
        }
        boolean ok;
        try { ok = BCrypt.checkpw(req.password, u.getPasswordHash()); }
        catch (Exception e) { ok = false; }
        if (!ok) return ResponseEntity.status(401).build();

        u.setLastLoginAt(Instant.now());
        repo.save(u);

        EffectivePermissions perms = permissionsService.resolve(u);

        String token = generateToken(u.getUsername(), List.of(u.getRole() == null ? "OPERATOR" : u.getRole()));
        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole());                     // legacy
        out.put("roleCode", perms.roleCode());            // nuevo
        out.put("roleName", perms.roleName());            // nuevo
        out.put("fullAccess", perms.fullAccess());        // nuevo (DIOS)
        out.put("enabledPanels", u.getEnabledPanels());   // legacy CSV
        out.put("panelKeys", perms.panelKeys());          // nuevo: lista efectiva
        out.put("canManageUsers", perms.canManageUsers());
        out.put("canManageRoles", perms.canManageRoles());
        return ResponseEntity.ok(out);
    }

    private String generateToken(String username, List<String> roles) {
        long now = System.currentTimeMillis();
        Date iat = new Date(now);
        Date exp = new Date(now + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        if (roles != null) {
            claims.put("roles", roles);
            if (!roles.isEmpty()) claims.put("role", roles.get(0));
        }
        claims.put("userKind", "PANEL_USER");

        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(iat)
                .expiration(exp)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
