package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.PaymentTitularDto;
import app.coincidir.api.web.admin.dto.SetPaymentTitularRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/payment-titular")
@RequiredArgsConstructor
public class PaymentTitularAdminController {

    private final PaymentTitularAdminService service;

    @GetMapping
    public PaymentTitularDto get(@PathVariable Long groupId) {
        return service.get(groupId);
    }

    @PutMapping
    public PaymentTitularDto set(
            @PathVariable Long groupId,
            @RequestBody(required = false) SetPaymentTitularRequest body
    ) {
        Long memberId = body == null ? null : body.memberId();
        return service.set(groupId, memberId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable Long groupId) {
        service.clear(groupId);
    }
}
