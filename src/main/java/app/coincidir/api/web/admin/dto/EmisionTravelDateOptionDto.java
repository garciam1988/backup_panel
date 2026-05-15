package app.coincidir.api.web.admin.dto;

/**
 * Opción para combo de "Fecha de viaje (en curso)": fecha de ida y cantidad de pasajeros emitidos en esa fecha.
 */
public record EmisionTravelDateOptionDto(
        String date,
        long count,
        String label
) {
}
