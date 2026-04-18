package app.coincidir.api.web.admin.dto;

/**
 * Request para setear el titular de pago de una operación (grupo).
 */
public record SetPaymentTitularRequest(
        Long memberId
) {}
