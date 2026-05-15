package app.coincidir.api.web.conciliation;

import app.coincidir.api.web.conciliation.dto.ConciliationExpenseDto;
import app.coincidir.api.web.conciliation.dto.MarkProblemRequest;
import app.coincidir.api.web.conciliation.dto.VerifyPaymentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({
        "/api/conciliation/expenses",
        "/api/conciliation/egresos",
        "/api/conciliation/spent",
        "/api/expenses",
        "/api/gastos"
})
@RequiredArgsConstructor
public class ExpensesConciliationController {

    private final ExpensesConciliationService service;

    @GetMapping
    public List<ConciliationExpenseDto> listExpenses() {
        return service.listExpenses();
    }

    @GetMapping("/{expenseId}/receipt")
    public ResponseEntity<byte[]> downloadReceipt(@PathVariable Long expenseId) {
        ExpensesConciliationService.ReceiptDownload r = service.downloadReceipt(expenseId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFileName(r.fileName()) + "\"")
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.bytes());
    }

    @PostMapping("/{expenseId}/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(
            @PathVariable Long expenseId,
            @RequestBody(required = false) VerifyPaymentRequest body
    ) {
        String bankReceiptNumber = body == null ? null : body.bankReceiptNumber();
        service.verifyExpense(expenseId, bankReceiptNumber);
    }


    @PostMapping("/{expenseId}/pending")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pendingAccreditation(@PathVariable Long expenseId) {
        service.markExpensePendingAccreditation(expenseId);
    }

    @PostMapping("/{expenseId}/problem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void problem(
            @PathVariable Long expenseId,
            @RequestBody(required = false) MarkProblemRequest body
    ) {
        String note = body == null ? null : body.note();
        service.markExpenseProblem(expenseId, note);
    }

    private String safeFileName(String name) {
        if (name == null) return "comprobante_gasto";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
