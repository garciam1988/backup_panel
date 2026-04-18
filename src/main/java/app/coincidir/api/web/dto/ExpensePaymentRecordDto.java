package app.coincidir.api.web.dto;

import app.coincidir.api.domain.expense.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only payment-like record based on Expenses.
 * Used to display payments that were created outside ServicePaymentRecord flow
 * (e.g., early payments from Admin Panel), and also to show the Operations-created
 * expense concept for service payments.
 *
 * Extra fields (memberId/passengerFullName/reservationCode/operationStatus) are optional and
 * mainly used by AEREOS in Operations panel.
 */
public record ExpensePaymentRecordDto(
        Long id,
        Long groupId,
        Long menuItemId,
        Long servicePaymentRecordId,
        LocalDate date,
        String type,
        String category,
        String concept,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        String status,
        String receiptNumber,
        String receiptLast4,
        Boolean hasReceipt,
        Long memberId,
        String passengerFullName,
        String reservationCode,
        String operationStatus
) {
    public static ExpensePaymentRecordDto fromEntity(Expense e) {
        if (e == null) return null;
        boolean hasReceipt = e.getReceiptBlob() != null && e.getReceiptBlob().length > 0;
        return new ExpensePaymentRecordDto(
                e.getId(),
                e.getGroupId(),
                e.getMenuItemId(),
                e.getServicePaymentRecordId(),
                e.getDate(),
                e.getType() != null ? e.getType().name() : null,
                e.getCategory(),
                e.getConcept(),
                e.getPaymentMethod(),
                e.getAmount(),
                e.getCurrency(),
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getReceiptNumber(),
                e.getReceiptLast4(),
                hasReceipt,
                null,
                null,
                null,
                null
        );
    }

    public static ExpensePaymentRecordDto enriched(
            Expense e,
            Long effectiveServicePaymentRecordId,
            Long memberId,
            String passengerFullName,
            String reservationCode,
            String operationStatus
    ) {
        if (e == null) return null;
        boolean hasReceipt = e.getReceiptBlob() != null && e.getReceiptBlob().length > 0;
        return new ExpensePaymentRecordDto(
                e.getId(),
                e.getGroupId(),
                e.getMenuItemId(),
                effectiveServicePaymentRecordId,
                e.getDate(),
                e.getType() != null ? e.getType().name() : null,
                e.getCategory(),
                e.getConcept(),
                e.getPaymentMethod(),
                e.getAmount(),
                e.getCurrency(),
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getReceiptNumber(),
                e.getReceiptLast4(),
                hasReceipt,
                memberId,
                passengerFullName,
                reservationCode,
                operationStatus
        );
    }
}
