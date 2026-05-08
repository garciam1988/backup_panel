package app.coincidir.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final String base64Secret;
    private final long ttlSeconds;

    private SecretKey key;
    private long ttlMillis;

    public JwtService(
            // SIN default. Si no está configurado, jjwt va a tirar un error
            // claro al construir el SecretKey. El admin sabrá que falta JWT_SECRET.
            @Value("${jwt.secret:${security.jwt.secret:}}") String base64Secret,
            @Value("${jwt.ttlSeconds:${security.jwt.ttl-seconds:43200}}") long ttlSeconds
    ) {
        this.base64Secret = base64Secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        // Usamos el decoder tolerante para aceptar Base64 estándar, URL-safe
        // o texto plano (ver JwtSecretDecoder).
        this.key = Keys.hmacShaKeyFor(JwtSecretDecoder.decode(base64Secret));
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** Genera un JWT HS256 con subject + claims extra */
    public String generate(String subject, Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        Date iat = new Date(now);
        Date exp = new Date(now + ttlMillis);

        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(iat)
                .expiration(exp)
                // con 0.12.x se recomienda pasar el alg explícito:
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Parsea y valida un token firmado, devolviendo los Claims */
    public Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }
}
