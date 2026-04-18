package app.coincidir.api.web.conciliation;

import app.coincidir.api.web.conciliation.dto.ConciliationPaymentDto;
import app.coincidir.api.web.conciliation.dto.MarkProblemRequest;
import app.coincidir.api.web.conciliation.dto.VerifyPaymentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conciliation/payments")
@RequiredArgsConstructor
public class PaymentsConciliationController {

    private final PaymentsConciliationService service;

    @GetMapping
    public List<ConciliationPaymentDto> listPayments() {
        return service.listPayments();
    }

    @PostMapping("/{paymentId}/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(
            @PathVariable Long paymentId,
            @RequestBody(required = false) VerifyPaymentRequest body
    ) {
        String bankReceiptNumber = body == null ? null : body.bankReceiptNumber();
        service.verifyPayment(paymentId, bankReceiptNumber);
    }


    @PostMapping("/{paymentId}/pending")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pendingAccreditation(@PathVariable Long paymentId) {
        service.markPaymentPendingAccreditation(paymentId);
    }

    @PostMapping("/{paymentId}/problem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void problem(
            @PathVariable Long paymentId,
            @RequestBody(required = false) MarkProblemRequest body
    ) {
        String note = body == null ? null : body.note();
        service.markPaymentProblem(paymentId, note);
    }
}
