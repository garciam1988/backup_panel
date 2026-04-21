package app.coincidir.api.panel;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
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
 * PanelAuthController — login de usuarios del /panel (separados del admin).
 *
 * Genera JWT con rol OPERATOR o PANEL_ADMIN. Los endpoints bajo /api/panel/**
 * requieren este JWT. /api/admin/** sigue requiriendo rol ADMIN.
 *
 * Genera el token INLINE con jjwt para NO depender de JwtTokenService
 * (que vive en app.coincidir.security — un package hermano que no escanea
 * el @SpringBootApplication de app.coincidir.api). La clave y el formato
 * son exactamente los mismos que usa el filtro existente, así que los
 * tokens resultantes son 100% compatibles.
 */
@Slf4j
@RestController
@RequestMapping("/api/panel/auth")
public class PanelAuthController {

    private final PanelUserRepository repo;

    private final String base64Secret;
    private final long ttlSeconds;

    private SecretKey signingKey;
    private long ttlMillis;

    public PanelAuthController(
            PanelUserRepository repo,
            // Mismas properties que JwtTokenService (con fallback a default)
            @Value("${security.jwt.secret:${jwt.secret:8k8L0p0vC9k14y3w3m1k+T1bJ3Vq1Q9WZzqQyvV6f1s8=}}") String base64Secret,
            @Value("${security.jwt.ttl-seconds:${jwt.ttlSeconds:43200}}") long ttlSeconds
    ) {
        this.repo = repo;
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

        String token = generateToken(u.getUsername(), List.of(u.getRole()));
        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole());
        out.put("enabledPanels", u.getEnabledPanels());
        return ResponseEntity.ok(out);
    }

    /** Replica exactamente la lógica de JwtTokenService.generateToken. */
    private String generateToken(String username, List<String> roles) {
        long now = System.currentTimeMillis();
        Date iat = new Date(now);
        Date exp = new Date(now + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        if (roles != null) {
            claims.put("roles", roles);
            if (!roles.isEmpty()) claims.put("role", roles.get(0));
        }

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
