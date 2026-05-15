package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MemberPaymentInstallmentDto(
        Long id,
        Integer installmentNumber,
        BigDecimal amount,
        LocalDate dueDate,
        LocalDate paidDate,
        String status
) {}
