package app.coincidir.api.web.admin.expenses.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ExpenseDto(
        Long id,
        LocalDate date,
        String type,
        String category,
        String concept,
        Long providerId,
        String providerName,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        String status,
        String receiptNumber,
        String receiptLast4,
        Boolean hasReceipt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
