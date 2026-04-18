package app.coincidir.api.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateGroupServiceMenuOrderRequest(
        @NotEmpty List<Long> orderedItemIds
) {}
