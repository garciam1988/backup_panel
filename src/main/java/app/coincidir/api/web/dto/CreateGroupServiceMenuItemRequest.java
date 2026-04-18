package app.coincidir.api.web.dto;

import jakarta.validation.constraints.NotNull;

public record CreateGroupServiceMenuItemRequest(
        @NotNull Long serviceId
) {}
