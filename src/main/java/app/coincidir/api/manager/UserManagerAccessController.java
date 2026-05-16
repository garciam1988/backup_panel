package app.coincidir.api.manager;

import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserManagerAccessController — endpoints chicos para que el AdminPanel pueda
 * listar todos los usuarios y cambiar el flag {@code manager_access}.
 *
 * Endpoints:
 *   GET  /api/admin/manager-users           → lista todos los user_account
 *   PUT  /api/admin/manager-users/{id}      → setea/quita manager_access
 *   GET  /api/me/manager-access             → { hasAccess: boolean }
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UserManagerAccessController {

    private final UserAccountRepository repo;

    @GetMapping("/api/admin/manager-users")
    public List<Map<String, Object>> listUsers() {
        return repo.findAll().stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("firstName", u.getFirstName());
            m.put("lastName", u.getLastName());
            m.put("role", u.getRole());
            m.put("managerAccess", Boolean.TRUE.equals(u.getManagerAccess()));
            m.put("lastLoginAt", u.getLastLoginAt());
            return m;
        }).collect(Collectors.toList());
    }

    @PutMapping("/api/admin/manager-users/{id}")
    @Transactional
    public Map<String, Object> setAccess(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        UserAccount u = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User no encontrado: " + id));
        Object val = body.get("managerAccess");
        if (!(val instanceof Boolean)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El body debe ser { managerAccess: true|false }");
        }
        boolean newVal = (Boolean) val;
        boolean oldVal = Boolean.TRUE.equals(u.getManagerAccess());
        u.setManagerAccess(newVal);
        repo.save(u);
        log.info("[manager-users] {} managerAccess {} → {} por {}",
                u.getEmail(), oldVal, newVal, auth == null ? "?" : auth.getName());

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", u.getId());
        resp.put("email", u.getEmail());
        resp.put("managerAccess", newVal);
        return resp;
    }

    @GetMapping("/api/me/manager-access")
    public Map<String, Object> myAccess(Authentication auth) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("hasAccess", false);
        if (auth == null || auth.getName() == null) return resp;
        repo.findByEmail(auth.getName()).ifPresent(u ->
                resp.put("hasAccess", Boolean.TRUE.equals(u.getManagerAccess())));
        return resp;
    }
}
