package app.coincidir.api.web.dto;

import java.math.BigDecimal;

/**
 * Item de catálogo para combos de excursiones.
 * departureTime/returnTime en formato HH:mm (pueden ser null).
 */
public record ExcursionCatalogItemDto(
        Long id,
        String name,
        BigDecimal cost,
        String departureTime,
        String returnTime
) {}
