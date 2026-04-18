package app.coincidir.api.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pago cargado desde Admin Panel (Listado de pasajeros -> Ver -> Aéreos).
 * Se persiste como pago de miembro (member_payment_*) y, además, como gasto/egreso para conciliación.
 */
public record CreateTravelRequestAirPaymentRequest(
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        @JsonAlias({"method", "oneTimeMethod", "one_time_method", "one_time_method", "payment_method"})
        String paymentMethod,
        String receiptLast4,
        String reservationCode,
        String concept,
        Base64ReceiptUpload receipt
) {

    public record Base64ReceiptUpload(
            String base64,
            String contentType,
            String fileName
    ) {}
}
