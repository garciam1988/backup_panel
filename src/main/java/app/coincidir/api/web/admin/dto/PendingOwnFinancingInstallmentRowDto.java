package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PendingOwnFinancingInstallmentRowDto(
        Long groupId,
        String destination,
        String whenLabel,
        Long memberId,
        String memberName,
        Integer totalInstallments,
        Integer paidInstallments,
        Integer pendingInstallments,
        Integer nextInstallmentNumber,
        LocalDate nextDueDate,
        BigDecimal nextAmount,
        String currency,
        LocalDate lastPaymentDate
) {}
