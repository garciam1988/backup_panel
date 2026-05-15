package app.coincidir.api.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
class DevController {
    private final PasswordEncoder encoder;

    @GetMapping("/hash")
    Map<String,String> hash(@RequestParam String raw) {
        return Map.of("raw", raw, "hash", encoder.encode(raw));
    }

    @GetMapping("/check")
    Map<String,Boolean> check(@RequestParam String raw, @RequestParam String hash) {
        return Map.of("matches", encoder.matches(raw, hash));
    }
}
