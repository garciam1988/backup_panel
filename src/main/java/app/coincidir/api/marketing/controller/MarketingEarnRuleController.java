package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyEarnRule;
import app.coincidir.api.marketing.dto.MarketingDtos.EarnRuleDto;
import app.coincidir.api.marketing.service.LoyaltyEarnRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MarketingEarnRuleController — CRUD de reglas dinámicas de earn.
 *
 * Endpoints:
 *   GET    /api/admin/marketing/rules              — lista paginada
 *   GET    /api/admin/marketing/rules/all          — lista completa (para UI)
 *   GET    /api/admin/marketing/rules/{id}         — detalle
 *   POST   /api/admin/marketing/rules              — crear
 *   PUT    /api/admin/marketing/rules/{id}         — actualizar
 *   DELETE /api/admin/marketing/rules/{id}         — borrar
 *   POST   /api/admin/marketing/rules/{id}/toggle  — activar/pausar rápido
 *
 * Todos los endpoints requieren JWT del panel admin (cubierto por
 * SecurityConfig que protege /api/admin/** excepto login).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing/rules")
@RequiredArgsConstructor
public class MarketingEarnRuleController {

    private final LoyaltyEarnRuleService ruleService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        Page<LoyaltyEarnRule> p = ruleService.list(page, size);
        return ResponseEntity.ok(Map.of(
            "items", p.getContent().stream().map(EarnRuleDto::fromEntity).toList(),
            "total", p.getTotalElements(),
            "page",  p.getNumber(),
            "size",  p.getSize()
        ));
    }

    @GetMapping("/all")
    public ResponseEntity<?> listAll() {
        List<LoyaltyEarnRule> all = ruleService.listAll();
        return ResponseEntity.ok(all.stream().map(EarnRuleDto::fromEntity).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ruleService.findById(id)
            .map(r -> ResponseEntity.ok((Object) EarnRuleDto.fromEntity(r)))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Regla no encontrada")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody EarnRuleDto dto) {
        try {
            LoyaltyEarnRule created = ruleService.create(dto.toEntity());
            return ResponseEntity.ok(EarnRuleDto.fromEntity(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creando regla", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody EarnRuleDto dto) {
        try {
            LoyaltyEarnRule updated = ruleService.update(id, dto.toEntity());
            return ResponseEntity.ok(EarnRuleDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        try {
            LoyaltyEarnRule r = ruleService.toggleActive(id);
            return ResponseEntity.ok(EarnRuleDto.fromEntity(r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
