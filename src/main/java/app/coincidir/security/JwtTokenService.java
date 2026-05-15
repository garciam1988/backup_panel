package app.coincidir.security;

import app.coincidir.api.security.JwtSecretDecoder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibilidad con jjwt 0.12.x (no existe Jwts.parserBuilder()).
 * Mantiene las firmas típicas usadas por el proyecto (generateToken/parseClaims).
 */
@Service
public class JwtTokenService {

    private final String base64Secret;
    private final long ttlSeconds;

    private SecretKey signingKey;
    private long ttlMillis;

    public JwtTokenService(
            // compat: primero security.jwt.* (application.yml), fallback a jwt.*.
            // SIN default — el secret tiene que venir de env var (JWT_SECRET).
            @Value("${security.jwt.secret:${jwt.secret:}}") String base64Secret,
            @Value("${security.jwt.ttl-seconds:${jwt.ttlSeconds:43200}}") long ttlSeconds
    ) {
        this.base64Secret = base64Secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        // Decoder tolerante: acepta Base64 estándar, URL-safe (con '-' / '_')
        // y texto plano. Ver JwtSecretDecoder.
        this.signingKey = Keys.hmacShaKeyFor(JwtSecretDecoder.decode(base64Secret));
        this.ttlMillis = ttlSeconds * 1000L;
    }

    public String generateToken(String username, List<String> roles) {
        long now = System.currentTimeMillis();
        Date iat = new Date(now);
        Date exp = new Date(now + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        if (roles != null) {
            claims.put("roles", roles);
            if (!roles.isEmpty()) {
                // compat con implementaciones que esperan un único rol
                claims.put("role", roles.get(0));
            }
        }

        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(iat)
                .expiration(exp)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
