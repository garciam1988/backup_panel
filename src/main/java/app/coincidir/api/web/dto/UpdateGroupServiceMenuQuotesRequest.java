package app.coincidir.api.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record UpdateGroupServiceMenuQuotesRequest(
        List<QuoteEntry> quotes
) {
    public record QuoteEntry(
            Long menuItemId,
            BigDecimal quotedValue
    ) {}
}
