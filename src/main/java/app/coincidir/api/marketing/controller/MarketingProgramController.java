package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.dto.MarketingDtos.ProgramDto;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * MarketingProgramController — Endpoints admin para la configuración del
 * programa de fidelización singleton.
 *
 * Todas las rutas requieren JWT panel.
 */
@RestController
@RequestMapping("/api/admin/marketing/program")
@RequiredArgsConstructor
public class MarketingProgramController {

    private final LoyaltyProgramService programService;

    @GetMapping
    public ProgramDto get() {
        return ProgramDto.fromEntity(programService.getActiveProgram());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody LoyaltyProgram body) {
        try {
            LoyaltyProgram updated = programService.update(id, body);
            return ResponseEntity.ok(ProgramDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    private java.util.Map<String, Object> error(String msg) {
        return java.util.Map.of("error", msg);
    }
}
