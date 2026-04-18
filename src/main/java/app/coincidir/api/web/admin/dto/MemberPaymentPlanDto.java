package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin-facing DTO for the payments module.
 *
 * planType: ONE_TIME | OWN_FINANCING
 * oneTimeMethod: TRANSFERENCIA | DEPOSITO | TARJETA_DEBITO | TARJETA_CREDITO
 */
public record MemberPaymentPlanDto(
        Long id,
        Long groupId,
        Long memberId,
        String planType,
        String oneTimeMethod,
        BigDecimal totalAmount,
        String currency,
        String receiptLast4,
        String notes,
        List<MemberPaymentInstallmentDto> installments
) {}
