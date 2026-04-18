package app.coincidir.api.web.admin;

import app.coincidir.api.service.AuthorizationCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/parameters/authorization")
@RequiredArgsConstructor
public class AdminAuthorizationController {

    private final AuthorizationCodeService authCodeService;

    public record ValidateAuthorizationCodeRequest(String code, String type) {}

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody ValidateAuthorizationCodeRequest body) {
        String code = body == null ? null : body.code();
        String type = body == null ? null : body.type();

        AuthorizationCodeService.ValidateResult res = authCodeService.validateAndGrant(type, code);
        if (res.valid()) {
            return ResponseEntity.ok(java.util.Map.of(
                    "valid", true,
                    "ttlSeconds", res.ttlSeconds()
            ));
        }
        return ResponseEntity.ok(java.util.Map.of(
                "valid", false,
                "message", res.message() == null ? "Código inválido" : res.message()
        ));
    }
}
