package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for registering a payment.
 */
public record CreateMemberPaymentRecordRequest(
        BigDecimal amount,
        LocalDate paymentDate,
        String receiptLast4,
        Integer installmentNumber
) {}
