package app.coincidir.api.web.conciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ConciliationPaymentDto(
        Long paymentId,
        Long groupId,
        Long memberId,
        String memberName,
        String whatsapp,
        String planType,
        Integer installmentNumber,
        Integer installmentsTotal,
        String paymentMethodUsed,
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        String receiptLast4,
        Boolean hasReceipt,
        String bankReceiptNumber,
        LocalDateTime createdAt,
        String conciliationStatus,
        String conciliationNote,
        LocalDateTime conciliationUpdatedAt
) {
}
