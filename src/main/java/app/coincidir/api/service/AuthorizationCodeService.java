package app.coincidir.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autorización por código (parametrizable) para acciones sensibles.
 *
 * Flujo esperado (no ADMIN):
 *  1) El FE valida el código llamando a /api/admin/parameters/authorization/validate
 *  2) Si es válido, el backend guarda un "grant" temporal por usuario
 *  3) Las acciones sensibles chequean ese grant mientras esté vigente
 */
@Service
@RequiredArgsConstructor
public class AuthorizationCodeService {

    private final JdbcTemplate jdbc;

    // Ventana de validez del grant (no requiere reenviar el código en cada request)
    private static final Duration GRANT_TTL = Duration.ofMinutes(10);

    // key: <type>|<userEmail> -> expiresAt
    private final ConcurrentHashMap<String, Instant> grants = new ConcurrentHashMap<>();

    public record ValidateResult(boolean valid, String message, long ttlSeconds) {}

    /**
     * Valida código y, si es correcto, habilita un grant temporal para el usuario autenticado.
     */
    public ValidateResult validateAndGrant(String type, String code) {
        Authentication auth = requireAuth();
        if (isAdmin(auth)) {
            // Un ADMIN no necesita validar código
            return new ValidateResult(true, null, GRANT_TTL.toSeconds());
        }

        String t = normalizeType(type);
        String expected = getExpectedCodeForType(t);
        if (expected == null || expected.isBlank()) {
            return new ValidateResult(false, "No hay un código configurado para " + t, 0);
        }

        String provided = code == null ? "" : code.trim();
        if (!Objects.equals(expected.trim(), provided)) {
            return new ValidateResult(false, "Código inválido", 0);
        }

        grant(auth.getName(), t);
        return new ValidateResult(true, null, GRANT_TTL.toSeconds());
    }

    /**
     * Requiere autorización (por grant temporal) si el usuario NO es ADMIN.
     */
    public void requireAuthorizationIfNotAdmin(String type) {
        Authentication auth = requireAuth();
        if (isAdmin(auth)) return;

        String t = normalizeType(type);
        String k = key(t, auth.getName());
        Instant exp = grants.get(k);
        if (exp == null || exp.isBefore(Instant.now())) {
            grants.remove(k);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Se requiere código de autorización para realizar esta acción"
            );
        }
    }

    /**
     * Reglas para cotización:
     * - Primera carga: current == null AND requested != null => NO requiere
     * - Modificación: current != null AND requested == null OR different => requiere
     */
    public void requireAuthorizationForQuoteChangeIfNeeded(
            java.math.BigDecimal currentQuotedValue,
            java.math.BigDecimal requestedQuotedValue
    ) {
        // IMPORTANT:
        // Varias pantallas envían updates parciales (no re-envían la cotización).
        // En esos casos requestedQuotedValue llega null, pero NO debe interpretarse
        // como "limpiar" el valor existente. Para evitar falsos 403/400, tratamos
        // requestedQuotedValue == null como "sin cambios".
        if (requestedQuotedValue == null) {
            return;
        }
        if (currentQuotedValue == null) {
            // primera carga o sigue vacío
            return;
        }

        boolean changing;
        changing = currentQuotedValue.compareTo(requestedQuotedValue) != 0;

        if (changing) {
            requireAuthorizationIfNotAdmin("ADMIN");
        }
    }

    private void grant(String userEmail, String type) {
        String k = key(type, userEmail);
        grants.put(k, Instant.now().plus(GRANT_TTL));
    }

    private static String key(String type, String userEmail) {
        return type + "|" + (userEmail == null ? "" : userEmail.toLowerCase());
    }

    private static String normalizeType(String type) {
        String t = (type == null ? "" : type.trim().toUpperCase());
        return t.isBlank() ? "ADMIN" : t;
    }

    private Authentication requireAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }
        return auth;
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga == null) continue;
            String a = ga.getAuthority();
            if (a == null) continue;
            if (a.equalsIgnoreCase("ROLE_ADMIN") || a.equalsIgnoreCase("ADMIN")) return true;
            if (a.equalsIgnoreCase("ROLE_OPERATIONS") || a.equalsIgnoreCase("OPERATIONS")) return true;
        }
        return false;
    }

    private String getExpectedCodeForType(String type) {
        // Mapeo flexible para permitir futuros códigos (SUPERVISOR, etc.)
        Map<String, List<String>> typeToKeys = Map.of(
                "ADMIN", List.of("VALIDATION_CODE_ADMIN", "CODIGO_VALIDACION_ADMINISTRADOR", "ADMIN_VALIDATION_CODE"),
                "SUPERVISOR", List.of("VALIDATION_CODE_SUPERVISOR", "CODIGO_VALIDACION_SUPERVISOR", "SUPERVISOR_VALIDATION_CODE")
        );

        List<String> candidates = typeToKeys.getOrDefault(type, List.of("VALIDATION_CODE_" + type));

        for (String k : candidates) {
            String v = queryParametroValue(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String queryParametroValue(String codeKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT value FROM parametros WHERE activo = 1 AND code = ? LIMIT 1",
                    String.class,
                    codeKey
            );
        } catch (EmptyResultDataAccessException ex) {
            return null;
        } catch (Exception ex) {
            // Si la tabla aún no existe en un entorno, no rompemos la app: devolvemos null
            return null;
        }
    }
}
