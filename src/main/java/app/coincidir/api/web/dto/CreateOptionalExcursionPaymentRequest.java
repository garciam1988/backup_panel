package app.coincidir.api.web.dto;

import java.math.BigDecimal;

/**
 * Payload usado por Admin/Operaciones en Opcionales > Excursiones.
 * Mantiene naming compatible con el frontend.
 */
public record CreateOptionalExcursionPaymentRequest(
        BigDecimal amountUsd,
        String paymentMethod,
        String paymentDate,   // yyyy-MM-dd
        String receiptLast4,
        Long bankId,
        String cardLast4
) {}
