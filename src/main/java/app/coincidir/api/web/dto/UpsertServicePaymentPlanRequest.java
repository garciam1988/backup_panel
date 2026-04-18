package app.coincidir.api.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpsertServicePaymentPlanRequest(
        @NotBlank String paymentForm,
        @NotNull BigDecimal totalAmount,
        String currency,
        @JsonAlias({"reservationCode", "reservation_code", "codigoReserva", "codigo_reserva", "codigoDeReserva"})
        String reservationCode
) {}
