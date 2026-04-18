package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.SendVoucherEmailRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/vouchers")
@RequiredArgsConstructor
public class VoucherEmailAdminController {

    private final VoucherEmailAdminService service;

    @PostMapping("/email")
    public ResponseEntity<?> sendVoucherEmail(@RequestBody SendVoucherEmailRequest body) {
        service.sendVoucherEmail(body);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Endpoint de diagnóstico SMTP — envía un mail simple de prueba.
     * Usar con: POST /api/admin/vouchers/test-smtp?to=tu@email.com
     */
    @PostMapping("/test-smtp")
    public ResponseEntity<?> testSmtp(@org.springframework.web.bind.annotation.RequestParam String to) {
        return service.testSmtp(to);
    }
}
