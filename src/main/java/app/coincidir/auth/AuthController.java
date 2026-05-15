package app.coincidir.auth;

import app.coincidir.security.JwtTokenService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
public class AuthController {

  private final JwtTokenService jwtTokenService;
  private final String adminUsername;
  private final String adminPassword;

  public AuthController(
      JwtTokenService jwtTokenService,
      @Value("${security.admin.username:admin}") String adminUsername,
      @Value("${security.admin.password:admin123}") String adminPassword) {
    this.jwtTokenService = jwtTokenService;
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    if (request == null
        || request.username() == null
        || request.password() == null
        || !adminUsername.equals(request.username())
        || !adminPassword.equals(request.password())) {
      return ResponseEntity.status(401).build();
    }

    String token = jwtTokenService.generateToken(request.username(), List.of("ADMIN"));
    return ResponseEntity.ok(new LoginResponse(token, request.username()));
  }

  public record LoginRequest(String username, String password) {}

  public record LoginResponse(String token, String username) {}
}
