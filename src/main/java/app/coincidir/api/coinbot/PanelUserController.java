package app.coincidir.api.coinbot;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * PanelUserController — CRUD de usuarios del /panel, gestionado desde /admin.
 *
 * Requiere JWT con rol ADMIN (vive bajo /api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/panel-users")
@RequiredArgsConstructor
public class PanelUserController {

    private final PanelUserRepository repo;

    @GetMapping
    @Transactional(readOnly = true)
    public List<PanelUserDto> list() {
        return repo.findAllByOrderByUsernameAsc().stream().map(PanelUserDto::from).toList();
    }

    @PostMapping
    @Transactional
    public PanelUserDto create(@RequestBody SaveRequest body, Authentication auth) {
        if (body.username == null || body.username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username obligatorio");
        if (body.password == null || body.password.length() < 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password mínimo 4 caracteres");

        String username = body.username.trim();
        if (repo.existsByUsername(username))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe ese username");

        PanelUser u = new PanelUser();
        u.setUsername(username);
        u.setDisplayName(body.displayName);
        u.setPasswordHash(BCrypt.hashpw(body.password, BCrypt.gensalt()));
        u.setRole(normalizeRole(body.role));
        u.setEnabledPanels(body.enabledPanels);
        u.setActive(body.active == null ? Boolean.TRUE : body.active);
        if (auth != null && auth.getName() != null) u.setCreatedBy(auth.getName());
        return PanelUserDto.from(repo.save(u));
    }

    @PutMapping("/{id}")
    @Transactional
    public PanelUserDto update(@PathVariable Long id, @RequestBody SaveRequest body) {
        PanelUser u = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body.displayName != null)    u.setDisplayName(body.displayName);
        if (body.role != null)           u.setRole(normalizeRole(body.role));
        if (body.enabledPanels != null)  u.setEnabledPanels(body.enabledPanels);
        if (body.active != null)         u.setActive(body.active);
        // Cambio de password solo si viene (y que tenga mínimo 4 chars)
        if (body.password != null && !body.password.isBlank()) {
            if (body.password.length() < 4)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password mínimo 4 caracteres");
            u.setPasswordHash(BCrypt.hashpw(body.password, BCrypt.gensalt()));
        }
        return PanelUserDto.from(repo.save(u));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        repo.deleteById(id);
    }

    private static String normalizeRole(String r) {
        if (r == null) return "OPERATOR";
        String n = r.trim().toUpperCase();
        return (n.equals("PANEL_ADMIN") || n.equals("OPERATOR")) ? n : "OPERATOR";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public String  username;
        public String  displayName;
        public String  password;      // opcional en update
        public String  role;
        public String  enabledPanels;
        public Boolean active;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PanelUserDto {
        public Long    id;
        public String  username;
        public String  displayName;
        public String  role;
        public String  enabledPanels;
        public Boolean active;
        public Instant lastLoginAt;
        public Instant createdAt;
        public String  createdBy;

        public static PanelUserDto from(PanelUser u) {
            PanelUserDto d = new PanelUserDto();
            d.id            = u.getId();
            d.username      = u.getUsername();
            d.displayName   = u.getDisplayName();
            d.role          = u.getRole();
            d.enabledPanels = u.getEnabledPanels();
            d.active        = u.getActive();
            d.lastLoginAt   = u.getLastLoginAt();
            d.createdAt     = u.getCreatedAt();
            d.createdBy     = u.getCreatedBy();
            return d;
        }
    }
}
