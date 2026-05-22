package app.coincidir.api.panel;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.JwtSecretDecoder;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.UserBranchAccess;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final UserBranchAccessRepository userBranchAccessRepo;
    private final BranchRepository branchRepo;

    private final String base64Secret;
    private final long ttlSeconds;

    private SecretKey signingKey;
    private long ttlMillis;

    public PanelAuthController(
            PanelUserRepository repo,
            UserBranchAccessRepository userBranchAccessRepo,
            BranchRepository branchRepo,
            // Mismas properties que JwtTokenService. SIN default — el secret
            // tiene que venir de env var (JWT_SECRET en Railway).
            @Value("${security.jwt.secret:${jwt.secret:}}") String base64Secret,
            @Value("${security.jwt.ttl-seconds:${jwt.ttlSeconds:43200}}") long ttlSeconds
    ) {
        this.repo = repo;
        this.userBranchAccessRepo = userBranchAccessRepo;
        this.branchRepo = branchRepo;
        this.base64Secret = base64Secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        // El decoder tolera Base64 estándar, Base64 URL-safe (con '-' y '_')
        // y texto plano. Esto evita errores como "Illegal base64 character: '-'"
        // cuando el secret se generó con `openssl rand -base64 32`.
        this.signingKey = Keys.hmacShaKeyFor(JwtSecretDecoder.decode(base64Secret));
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

        // ── Tenancy: resolver branches accesibles ─────────────────────────
        //
        // Misma lógica que /api/admin/auth/login:
        //   - DIOS: acceso universal (allBranches=true, branchIds=[]).
        //     El frontend del panel le muestra selector con todas las branches.
        //   - Cualquier otro rol: debe tener al menos una fila en
        //     user_branch_access. Si no, 403 con mensaje claro.
        //   - Si tiene varias, mandamos todas + la preferida. El frontend
        //     muestra selector si son varias, o chip read-only si es una.
        boolean isDios = "DIOS".equalsIgnoreCase(u.getRole());
        List<Long> branchIds = new ArrayList<>();
        Long preferredBranchId = null;
        List<Map<String, Object>> branchesPayload = new ArrayList<>();

        if (!isDios) {
            List<UserBranchAccess> accesses = userBranchAccessRepo.findByUserId(u.getId());
            if (accesses.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tu usuario no tiene sucursales asignadas. Pedile al administrador que te asigne al menos una.");
            }
            for (UserBranchAccess a : accesses) {
                branchIds.add(a.getBranchId());
                if (Boolean.TRUE.equals(a.getIsPreferred())) {
                    preferredBranchId = a.getBranchId();
                }
            }
            if (preferredBranchId == null && !branchIds.isEmpty()) {
                preferredBranchId = branchIds.get(0);
            }
            // Cargamos info legible (name, slug, address) para el frontend del panel
            // que va a mostrar el chip / selector. Solo activas — una sucursal
            // inactiva no debería ser elegible aunque el user la tenga asignada.
            for (Long bid : branchIds) {
                branchRepo.findById(bid).ifPresent(b -> {
                    if (!Boolean.FALSE.equals(b.getActive())) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", b.getId());
                        m.put("slug", b.getSlug());
                        m.put("name", b.getName());
                        m.put("address", b.getAddress());
                        branchesPayload.add(m);
                    }
                });
            }
            // Caso edge: el user tiene branches asignadas pero todas están
            // inactivas → tratamos como "sin branches" para no dejarlo entrar
            // a un panel que no puede operar.
            if (branchesPayload.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Las sucursales asignadas a tu usuario están inactivas. Pedile al administrador que las revise.");
            }
        } else {
            // DIOS: cargamos TODAS las branches activas para que pueda elegir.
            branchRepo.findAll().forEach(b -> {
                if (!Boolean.FALSE.equals(b.getActive())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("slug", b.getSlug());
                    m.put("name", b.getName());
                    m.put("address", b.getAddress());
                    branchesPayload.add(m);
                }
            });
        }

        u.setLastLoginAt(Instant.now());
        repo.save(u);

        String token = generateToken(u.getUsername(), List.of(u.getRole()),
                u.getId(), isDios, branchIds, preferredBranchId);

        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole());
        out.put("enabledPanels", u.getEnabledPanels());
        // Info de tenancy para que el frontend pueda mostrar el chip / selector
        // sin tener que parsear el JWT.
        out.put("allBranches", isDios);
        out.put("branches", branchesPayload);
        out.put("preferredBranchId", preferredBranchId);
        return ResponseEntity.ok(out);
    }

    /** Replica exactamente la lógica de JwtTokenService.generateToken con tenancy. */
    private String generateToken(String username, List<String> roles, Long uid,
                                  boolean allBranches, List<Long> branchIds,
                                  Long preferredBranchId) {
        long now = System.currentTimeMillis();
        Date iat = new Date(now);
        Date exp = new Date(now + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        if (uid != null) claims.put("uid", uid);
        if (roles != null) {
            claims.put("roles", roles);
            if (!roles.isEmpty()) claims.put("role", roles.get(0));
        }
        // userKind: marca explícita de que el JWT viene del panel (no del admin).
        // Lo usa el BranchResolverFilter para distinguir contextos si en algún
        // momento las reglas divergen.
        claims.put("userKind", "PANEL_USER");
        // Claims de tenancy — el BranchResolverFilter los lee para resolver
        // automáticamente la branch del request sin que el frontend mande
        // X-Branch-Id (aunque también puede mandarlo si quiere override).
        claims.put("allBranches", allBranches);
        claims.put("branchIds", branchIds != null ? branchIds : List.of());
        if (preferredBranchId != null) {
            claims.put("preferredBranchId", preferredBranchId);
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
