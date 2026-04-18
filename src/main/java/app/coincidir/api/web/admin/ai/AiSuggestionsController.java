package app.coincidir.api.web.admin.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai-suggestions")
@RequiredArgsConstructor
public class AiSuggestionsController {

    private final AiSuggestionsService service;

    @GetMapping("/status")
    public AiStatusDto status() {
        return service.getStatus();
    }

    @GetMapping
    public List<AiSuggestionDto> list() {
        return service.listActive();
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable Long id) {
        service.dismiss(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/suppress-finding")
    public ResponseEntity<?> suppressFinding(@PathVariable Long id, @RequestBody SuppressFindingRequest req) {
        if (req.findingTitle() == null || req.findingTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "findingTitle requerido"));
        }
        service.suppressFinding(id, req.findingTitle());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/not-applicable")
    public ResponseEntity<?> notApplicable(@RequestBody NotApplicableRequest req) {
        if (req.findingTitle() == null || req.findingTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "findingTitle requerido"));
        }
        service.addNotApplicable(req.findingTitle(), req.findingDescription(), req.userReason());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/run-now")
    public ResponseEntity<?> runNow() {
        service.runAnalysisAsync();
        return ResponseEntity.accepted().body(Map.of("message", "Análisis iniciado en segundo plano"));
    }
}
