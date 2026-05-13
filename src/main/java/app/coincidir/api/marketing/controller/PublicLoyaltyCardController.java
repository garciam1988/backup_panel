package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.dto.MarketingDtos.CardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CustomerDto;
import app.coincidir.api.marketing.dto.MarketingDtos.ProgramDto;
import app.coincidir.api.marketing.dto.MarketingDtos.PublicCardView;
import app.coincidir.api.marketing.dto.MarketingDtos.RewardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.TransactionDto;
import app.coincidir.api.marketing.service.LoyaltyCardService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PublicLoyaltyCardController — Vista pública de la tarjeta del cliente.
 *
 * Accesible desde la PWA usando solo el customer_hash en la URL. NO requiere
 * JWT. La seguridad se basa en que el hash es opaco (21 chars URL-safe,
 * espacio 64^21) y solo lo conoce el cliente.
 *
 * URL pública: GET /api/public/loyalty/card/{customerHash}
 *
 * Devuelve un PublicCardView consolidado con todo lo que la PWA necesita
 * para renderizar la pantalla principal en una sola llamada:
 *   - Config del programa (colores, métodos, etc).
 *   - Datos del cliente.
 *   - Estado actual de la tarjeta.
 *   - Premios disponibles (filtrados por vigencia y stock).
 *   - Últimas transacciones (para el feed).
 */
@RestController
@RequestMapping("/api/public/loyalty")
@RequiredArgsConstructor
public class PublicLoyaltyCardController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyProgramService programService;
    private final LoyaltyRewardService rewardService;
    private final LoyaltyTransactionService transactionService;

    @GetMapping("/card/{customerHash}")
    public ResponseEntity<?> getCard(@PathVariable String customerHash) {
        return customerService.findByHash(customerHash).map(cust -> {
            var program = programService.getActiveProgram();
            var card = cardService.getOrCreate(cust);
            var rewards = rewardService.listAvailableNow(program.getId());
            var recent = transactionService.recent(cust.getId());

            return ResponseEntity.ok((Object) new PublicCardView(
                ProgramDto.fromEntity(program),
                CustomerDto.fromEntity(cust),
                CardDto.fromEntity(card),
                rewards.stream().map(RewardDto::fromEntity).toList(),
                recent.stream().map(TransactionDto::fromEntity).toList()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/card/{customerHash}/preferences")
    public ResponseEntity<?> updatePrefs(@PathVariable String customerHash,
                                         @RequestBody Map<String, Boolean> body) {
        try {
            LoyaltyCustomer updated = customerService.updateCommunicationPrefs(
                customerHash,
                body.get("acceptsWhatsapp"),
                body.get("acceptsEmail"),
                body.get("acceptsPush")
            );
            return ResponseEntity.ok(CustomerDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
