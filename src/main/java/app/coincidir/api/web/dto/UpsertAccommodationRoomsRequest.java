package app.coincidir.api.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertAccommodationRoomsRequest {

    @JsonAlias({"roomCount", "roomsCount", "rooms_count", "cantidadHabitaciones", "cantHabitaciones", "habitaciones"})
    private Integer roomCount;

    @JsonAlias({"rooms", "roomsDistribution", "distribucionHabitaciones", "habitacionesDetalle"})
    private List<AccommodationRoomDistributionDto> rooms;
}
