package app.coincidir.api.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CreateServicePaymentRecordRequest(
        @NotNull BigDecimal amount,
        String currency,
        @NotNull String paymentDate,          // yyyy-MM-dd

        @JsonAlias({"totalPaymentCancellationDate", "total_payment_cancellation_date", "fechaCancelacionPagoTotal", "fecha_cancelacion_pago_total", "fechaCancelacionTotal", "fecha_cancelacion_total"})
        String totalPaymentCancellationDate, // yyyy-MM-dd (solo para Seña)
        @NotNull String oneTimeMethod,        // EFECTIVO / TRANSFERENCIA / TARJETA_DEBITO / TARJETA_CREDITO / etc.
        String receiptNumber,
        String receiptLast4,

        // Solo obligatorio para AÉREOS (por emisión)
        @JsonAlias({"reservationCode", "reservation_code", "codigoReserva", "codigo_reserva", "codigoDeReserva"})
        String reservationCode,


        @JsonAlias({"flightNumber", "flight_number", "nroVuelo", "nro_vuelo", "numeroVuelo", "numero_vuelo"})
        String flightNumber,
        // Solo para AÉREOS
        Long memberId,
        String passengerFullName,

        // Tarjeta (solo para oneTimeMethod TARJETA_DEBITO / TARJETA_CREDITO)
        Long bankId,
        Long cardId,
        @JsonAlias({"cardNumber", "card_number", "nroTarjeta", "nro_tarjeta", "numeroTarjeta", "numero_tarjeta"})        String cardNumber,

        // AÉREOS: pago por múltiples pasajeros (batch)
        @JsonAlias({"memberIds", "member_ids", "passengerIds", "passenger_ids", "selectedMemberIds", "selected_member_ids"})
        List<Long> memberIds,
        @JsonAlias({"passengersCount", "passengers_count", "paxCount", "pax_count"})
        Integer passengersCount
) {}
