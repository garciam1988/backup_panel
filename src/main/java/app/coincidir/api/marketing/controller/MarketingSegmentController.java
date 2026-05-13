package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingSegment;
import app.coincidir.api.marketing.dto.MarketingDtos.CustomerDto;
import app.coincidir.api.marketing.dto.MarketingDtos.SegmentDto;
import app.coincidir.api.marketing.dto.MarketingDtos.SegmentPreviewResponse;
import app.coincidir.api.marketing.service.MarketingSegmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MarketingSegmentController — CRUD de segmentos + preview de matches.
 */
@RestController
@RequestMapping("/api/admin/marketing/segments")
@RequiredArgsConstructor
public class MarketingSegmentController {

    private final MarketingSegmentService segmentService;

    @GetMapping
    public List<SegmentDto> list() {
        return segmentService.listAll().stream().map(SegmentDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SegmentDto> getOne(@PathVariable Long id) {
        return segmentService.findById(id)
            .map(s -> ResponseEntity.ok(SegmentDto.fromEntity(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody MarketingSegment body) {
        try {
            MarketingSegment saved = segmentService.create(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(SegmentDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody MarketingSegment body) {
        try {
            MarketingSegment saved = segmentService.update(id, body);
            return ResponseEntity.ok(SegmentDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            segmentService.softDelete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Evalúa el segmento existente y devuelve count + muestra. */
    @PostMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable Long id,
                                     @RequestParam(value = "sample", defaultValue = "10") int sample) {
        return segmentService.findById(id).map(s -> {
            List<LoyaltyCustomer> matches = segmentService.evaluate(s);
            return ResponseEntity.ok((Object) new SegmentPreviewResponse(
                matches.size(),
                matches.stream().limit(sample).map(CustomerDto::fromEntity).toList()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Evalúa un criteria_json ad-hoc sin guardar segmento. */
    @PostMapping("/preview")
    public ResponseEntity<?> previewAdhoc(@RequestBody Map<String, Object> body,
                                          @RequestParam(value = "sample", defaultValue = "10") int sample) {
        try {
            String criteriaJson = String.valueOf(body.get("criteriaJson"));
            List<LoyaltyCustomer> matches = segmentService.evaluateAdhoc(criteriaJson);
            return ResponseEntity.ok(new SegmentPreviewResponse(
                matches.size(),
                matches.stream().limit(sample).map(CustomerDto::fromEntity).toList()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
