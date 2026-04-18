package app.coincidir.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
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
            // compat: primero security.jwt.* (application.yml), fallback a jwt.*
            @Value("${security.jwt.secret:${jwt.secret:8k8L0p0vC9k14y3w3m1k+T1bJ3Vq1Q9WZzqQyvV6f1s8=}}") String base64Secret,
            @Value("${security.jwt.ttl-seconds:${jwt.ttlSeconds:43200}}") long ttlSeconds
    ) {
        this.base64Secret = base64Secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
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
