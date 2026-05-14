package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyTransaction;
import app.coincidir.api.marketing.dto.MarketingDtos.CardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CustomerDto;
import app.coincidir.api.marketing.dto.MarketingDtos.EnrollRequest;
import app.coincidir.api.marketing.dto.MarketingDtos.EnrollResponse;
import app.coincidir.api.marketing.dto.MarketingDtos.TransactionDto;
import app.coincidir.api.marketing.service.LoyaltyCardService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MarketingCustomerController — Listado/búsqueda/detalle/enrolamiento admin.
 */
@RestController
@RequestMapping("/api/admin/marketing/customers")
@RequiredArgsConstructor
public class MarketingCustomerController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyTransactionService transactionService;
    private final app.coincidir.api.marketing.repository.NotificationLogRepository notificationLogRepo;

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<LoyaltyCustomer> p = customerService.list(q,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "enrolledAt")));
        return Map.of(
            "items", p.getContent().stream().map(CustomerDto::fromEntity).toList(),
            "total", p.getTotalElements(),
            "page", p.getNumber(),
            "size", p.getSize()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        return customerService.findById(id).map(cust -> {
            var card = cardService.findByCustomerId(cust.getId()).orElse(null);
            List<LoyaltyTransaction> recent = transactionService.recent(cust.getId());
            return ResponseEntity.ok((Object) Map.of(
                "customer", CustomerDto.fromEntity(cust),
                "card", CardDto.fromEntity(card),
                "recentTransactions", recent.stream().map(TransactionDto::fromEntity).toList()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> enroll(@RequestBody EnrollRequest req) {
        try {
            var res = customerService.enrollOrReactivate(new LoyaltyCustomerService.EnrollInput(
                req.phone(), req.firstName(), req.lastName(), req.email(),
                req.birthDate(), req.branchId(),
                req.source() != null ? req.source() : "admin_manual",
                req.reservationTableSlug(), req.reservationRecordId()
            ));
            return ResponseEntity.ok(new EnrollResponse(
                res.customer().getId(),
                res.customer().getCustomerHash(),
                null,
                res.alreadyExisted()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Ajuste manual del balance del cliente (admin only). Permite sumar O restar
     * estampillas/puntos/cashback con un motivo obligatorio.
     *
     * Body:
     *   { "stampsDelta": 1, "pointsDelta": -50, "cashbackDelta": 0, "notes": "..." }
     *
     * Deltas positivos = sumar. Deltas negativos = restar. Al menos uno debe ser != 0.
     * `notes` es obligatorio para que quede trazabilidad de por qué el admin ajustó.
     *
     * Crea una LoyaltyTransaction con transaction_type='adjustment' y source='admin_manual'.
     * El historial queda inmutable: si se quiere revertir, se hace OTRO ajuste inverso.
     */
    @PostMapping("/{id}/adjust")
    public ResponseEntity<?> adjust(@PathVariable Long id,
                                    @RequestBody AdjustRequest req,
                                    org.springframework.security.core.Authentication auth) {
        if (req == null) return ResponseEntity.badRequest().body(Map.of("error", "Body requerido"));
        if (req.notes() == null || req.notes().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El motivo (notes) es obligatorio para ajustes manuales"));
        }
        int s = req.stampsDelta() == null ? 0 : req.stampsDelta();
        int p = req.pointsDelta() == null ? 0 : req.pointsDelta();
        java.math.BigDecimal c = req.cashbackDelta() == null ? java.math.BigDecimal.ZERO : req.cashbackDelta();
        if (s == 0 && p == 0 && c.signum() == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Al menos un delta debe ser distinto de cero"));
        }
        if (customerService.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
        }
        String performedBy = auth != null ? auth.getName() : "admin";
        try {
            var result = transactionService.record(new LoyaltyTransactionService.RecordInput(
                id, "adjustment", s, p, c, null, null, null, null, null, null, null,
                "admin_manual", performedBy, req.notes().trim()
            ));
            return ResponseEntity.ok(Map.of(
                "transactionId", result.transaction().getId(),
                "card", CardDto.fromEntity(result.card())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record AdjustRequest(
        Integer stampsDelta,
        Integer pointsDelta,
        java.math.BigDecimal cashbackDelta,
        String notes
    ) {}

    /**
     * Lista las últimas comunicaciones enviadas a un cliente (WhatsApp, email,
     * push). Útil para la pestaña "Comunicaciones" del detalle del cliente.
     *
     * Devuelve hasta 50 logs ordenados por queuedAt desc.
     */
    @GetMapping("/{id}/notifications")
    public ResponseEntity<?> notifications(@PathVariable Long id) {
        if (customerService.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
        }
        var page = notificationLogRepo.findByCustomerIdOrderByQueuedAtDesc(
            id, org.springframework.data.domain.PageRequest.of(0, 50));
        return ResponseEntity.ok(page.getContent());
    }

    /**
     * Toggle de preferencias de comunicación de un cliente (los 3 canales).
     * Body: { "acceptsWhatsapp": true/false, "acceptsEmail": true/false, "acceptsPush": true/false }
     * Permite mandar solo los flags que querés cambiar.
     */
    @PutMapping("/{id}/preferences")
    public ResponseEntity<?> updatePreferences(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        var custOpt = customerService.findById(id);
        if (custOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Cliente no encontrado"));
        }
        try {
            var updated = customerService.updateCommunicationPrefs(
                custOpt.get().getCustomerHash(),
                body.get("acceptsWhatsapp"),
                body.get("acceptsEmail"),
                body.get("acceptsPush")
            );
            return ResponseEntity.ok(CustomerDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
