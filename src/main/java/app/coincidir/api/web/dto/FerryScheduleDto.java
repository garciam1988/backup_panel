package app.coincidir.api.web.dto;

public record FerryScheduleDto(
        Long id,
        String provider,
        String busOrigin,
        String busDestination,
        String ferryOrigin,
        String ferryDestination,
        String busDepartureTime,
        String busArrivalTime,
        String ferryDepartureTime,
        String ferryArrivalTime
) {}
