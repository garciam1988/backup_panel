package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin-facing DTO for the payments log ("Últimos pagos").
 */
public record MemberPaymentRecordDto(
        Long id,
        Long groupId,
        Long memberId,
        String planType,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        String receiptLast4,
        LocalDateTime createdAt,
        boolean hasReceipt
) {}
