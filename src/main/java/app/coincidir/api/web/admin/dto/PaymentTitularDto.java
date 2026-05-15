package app.coincidir.api.web.admin.dto;

/**
 * Titular de pago a nivel operación (caso INDIVIDUAL).
 *
 * memberId: pasajero/titular seleccionado.
 * hasPayment: indica si el titular ya tiene al menos un pago registrado en member_payment_record.
 */
public record PaymentTitularDto(
        Long memberId,
        boolean hasPayment
) {}
