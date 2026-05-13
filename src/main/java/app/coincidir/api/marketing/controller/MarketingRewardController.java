package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyReward;
import app.coincidir.api.marketing.dto.MarketingDtos.RewardDto;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MarketingRewardController — CRUD del catálogo de premios.
 */
@RestController
@RequestMapping("/api/admin/marketing/rewards")
@RequiredArgsConstructor
public class MarketingRewardController {

    private final LoyaltyRewardService rewardService;
    private final LoyaltyProgramService programService;

    @GetMapping
    public List<RewardDto> list() {
        Long programId = programService.getActiveProgram().getId();
        return rewardService.listForAdmin(programId).stream().map(RewardDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RewardDto> getOne(@PathVariable Long id) {
        return rewardService.findById(id)
            .map(r -> ResponseEntity.ok(RewardDto.fromEntity(r)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody LoyaltyReward body) {
        try {
            LoyaltyReward saved = rewardService.create(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(RewardDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody LoyaltyReward body) {
        try {
            LoyaltyReward saved = rewardService.update(id, body);
            return ResponseEntity.ok(RewardDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            rewardService.softDelete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
