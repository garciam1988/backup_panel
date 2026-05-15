package app.coincidir.api.web.conciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO para conciliación de egresos/gastos provenientes del spent_panel (tabla expenses).
 */
public record ConciliationExpenseDto(
        Long id,
        Long groupId,
        LocalDate expenseDate,
        String type,
        String category,
        String concept,
        Long providerId,
        String providerName,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        String receiptLast4,
        String receiptNumber,
        Boolean hasReceipt,
        String bankReceiptNumber,
        String conciliationStatus,
        String conciliationNote,
        LocalDateTime conciliationUpdatedAt,
        LocalDateTime createdAt
) {
}
