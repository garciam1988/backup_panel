package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OwnFinancingInstallmentCollectionRowDto(
        Long operationId,
        Long groupId,
        Long memberId,
        String memberName,
        String memberPhone,
        String destination,
        String whenLabel,
        Long installmentId,
        Integer installmentNumber,
        Integer installmentsTotal,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        String status,
        Long paymentId
) {
}
