package app.coincidir.api.web.dto;

import app.coincidir.api.domain.FerryTripType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertGroupFerryRequest {

    private FerryTripType tripType;      // ONE_WAY / ROUND_TRIP

    private String originPort;
    private String destinationPort;

    private String departureDate;        // ida (yyyy-MM-dd, obligatorio)
    private String returnDate;           // vuelta (yyyy-MM-dd, opcional si ONE_WAY)

    private String ferryCompany;

    /** Prestador del servicio */
    private String provider;

    @JsonAlias({"reservationCode", "codigoReserva", "codigo_reserva"})
    private String reservationCode;

    private String departureTime;        // hora ida HH:mm (obligatoria)
    private String returnTime;           // hora vuelta HH:mm (obligatoria si ROUND_TRIP)

    private String notes;

    private String departureArrivalTime;
    private String returnArrivalTime;

    // --- Bus fields ---
    private String busOrigin;
    private String busDestination;
    private String busDepartureTime;
    private String busArrivalTime;
    private String returnBusDepartureTime;
    private String returnBusArrivalTime;

    @JsonAlias({"totalCosto"})
    private java.math.BigDecimal totalCost;

    private java.math.BigDecimal quotedValue;

}