package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstallmentCollectionRowDto(
        Long operationId,
        Long groupId,
        Long memberId,
        String memberName,
        String destination,
        Integer installmentNumber,
        Integer installmentsTotal,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        String whatsapp,
        String status
) {}
