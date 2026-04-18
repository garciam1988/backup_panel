package app.coincidir.api.auth;

import app.coincidir.api.auth.dto.*;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.UserAccountRepository;
import app.coincidir.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserAccountRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;


    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        System.out.println(">>> LOGIN REQUEST OK");
        UserAccount user = userRepo.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!encoder.matches(req.password(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // Guardamos última conexión
        user.setLastLoginAt(Instant.now());
        userRepo.save(user);

        String token = jwt.generate(
                user.getEmail(),
                Map.of("uid", user.getId(), "role", user.getRole())
        );
        return new LoginResponse(token);
    }

    @GetMapping("/me")
    public UserMeDto me(Principal principal) {
        // principal.getName() = email puesto en SecurityContext
        UserAccount user = userRepo.findByEmail(principal.getName()).orElseThrow();
        return new UserMeDto(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getFirstName(),
                user.getLastName(),
                user.getLastLoginAt()
        );
    }


}
