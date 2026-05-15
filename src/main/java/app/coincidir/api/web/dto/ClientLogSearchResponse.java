package app.coincidir.api.web.dto;

import java.util.List;

public record ClientLogSearchResponse(
        List<ClientLogEventDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
