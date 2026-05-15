package app.coincidir.api.web.admin.expenses.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request para crear/editar gastos.
 *
 * Campos alineados con spent_panel.
 */
public record ExpenseUpsertRequest(
        LocalDate date,
        String type,
        String category,
        String concept,
        Long providerId,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        String status,
        String receiptNumber,
        String notes
) {
}
