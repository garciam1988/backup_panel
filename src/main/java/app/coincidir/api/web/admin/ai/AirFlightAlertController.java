package app.coincidir.api.web.admin.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/air-flight-alerts")
@RequiredArgsConstructor
public class AirFlightAlertController {

    private final AirFlightAlertService service;

    @GetMapping("/status")
    public AirFlightAlertStatusDto status() {
        return service.getStatus();
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("issues", service.countActiveIssues());
    }

    @GetMapping
    public List<AirFlightAlertDto> listAll(
            @RequestParam(value = "issuesOnly", required = false, defaultValue = "false") boolean issuesOnly
    ) {
        return issuesOnly ? service.listIssues() : service.listAll();
    }

    @PostMapping("/run-now")
    public ResponseEntity<?> runNow() {
        service.runAsync();
        return ResponseEntity.accepted().body(Map.of("message", "Análisis iniciado en segundo plano"));
    }

    @PostMapping("/fix-data")
    public ResponseEntity<?> fixData() {
        service.countActiveIssues(); // llama fixNullDismissed internamente
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/visto")
    public ResponseEntity<?> marcarVisto(@PathVariable Long id) {
        service.marcarVisto(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/ignorar-permanentemente")
    public ResponseEntity<?> ignorarPermanentemente(@PathVariable Long id) {
        service.ignorarPermanentemente(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
