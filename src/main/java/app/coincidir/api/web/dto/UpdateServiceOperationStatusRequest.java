package app.coincidir.api.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateServiceOperationStatusRequest(
        @NotBlank String statusCode,
        String reservationDueDate
) {}
