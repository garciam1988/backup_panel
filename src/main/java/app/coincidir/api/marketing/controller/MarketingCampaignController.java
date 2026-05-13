package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.marketing.dto.MarketingDtos.CampaignDto;
import app.coincidir.api.marketing.service.MarketingCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MarketingCampaignController — CRUD + lanzamiento + cancelación.
 *
 * Endpoints clave:
 *   POST   /                  Crear (queda en DRAFT)
 *   POST   /{id}/launch       Lanza (IMMEDIATE → RUNNING; SCHEDULED → espera)
 *   POST   /{id}/cancel       Cancela una DRAFT/SCHEDULED
 *   GET    /{id}/recipients   Listado de destinatarios y estados
 */
@RestController
@RequestMapping("/api/admin/marketing/campaigns")
@RequiredArgsConstructor
public class MarketingCampaignController {

    private final MarketingCampaignService campaignService;

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<MarketingCampaign> p = campaignService.list(PageRequest.of(page, size));
        return Map.of(
            "items", p.getContent().stream().map(CampaignDto::fromEntity).toList(),
            "total", p.getTotalElements(),
            "page", p.getNumber(),
            "size", p.getSize()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto> getOne(@PathVariable Long id) {
        return campaignService.findById(id)
            .map(c -> ResponseEntity.ok(CampaignDto.fromEntity(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody MarketingCampaign body) {
        try {
            MarketingCampaign saved = campaignService.create(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(CampaignDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody MarketingCampaign body) {
        try {
            MarketingCampaign saved = campaignService.update(id, body);
            return ResponseEntity.ok(CampaignDto.fromEntity(saved));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/launch")
    public ResponseEntity<?> launch(@PathVariable Long id) {
        try {
            MarketingCampaign saved = campaignService.launch(id);
            return ResponseEntity.ok(CampaignDto.fromEntity(saved));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            campaignService.cancel(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/recipients")
    public ResponseEntity<?> recipients(@PathVariable Long id,
                                        @RequestParam(value = "max", defaultValue = "100") int max) {
        return ResponseEntity.ok(campaignService.recipientsForCampaign(id, max));
    }
}
