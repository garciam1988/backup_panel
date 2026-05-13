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
}
