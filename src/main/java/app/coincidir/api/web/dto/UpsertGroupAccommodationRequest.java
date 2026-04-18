package app.coincidir.api.web.dto;

import app.coincidir.api.domain.AccommodationRegimen;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import app.coincidir.api.domain.AccommodationContractType;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertGroupAccommodationRequest {

    private String name;

    private String checkInDate;   // yyyy-MM-dd
    private String checkInTime;   // HH:mm

    private String checkOutDate;  // yyyy-MM-dd
    private String checkOutTime;  // HH:mm

    private AccommodationRegimen regimen;

    private String country;
    private String city;

    private AccommodationContractType contractType;
    private String thirdPartyName;

    /**
     * Proveedor (Alojamiento). Alias funcional de thirdPartyName para compatibilidad con el front.
     */
    @JsonAlias({"provider", "providerName", "proveedor"})
    private String provider;


    @JsonAlias({"providerId", "idProveedor", "proveedorId"})
    private Long providerId;

    @JsonAlias({"accommodationId", "prestadorId", "alojamientoId"})
    private Long accommodationId;

    @JsonAlias({"reservationCode", "codigoReserva", "codigo_reserva"})
    private String reservationCode;

    @JsonAlias({"reservationAmount", "importeReserva", "importe_reserva"})
    private java.math.BigDecimal reservationAmount;


    @JsonAlias({"totalCosto"})
    private java.math.BigDecimal totalCost;

    private java.math.BigDecimal quotedValue;

    // yyyy-MM-dd
    private String reservationDueDate;

    // Habitaciones (distribución)
    @JsonAlias({"roomsCount", "rooms_count", "habitacionesCount", "cantidadHabitaciones", "cantHabitaciones"})
    private Integer roomsCount;

    @JsonAlias({"rooms", "roomsDistribution", "habitacionesDetalle", "distribucionHabitaciones"})
    private List<AccommodationRoomDistributionDto> rooms;

}