package app.coincidir.api.web.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertGroupTransferRequest {

    @JsonAlias({"tripType", "trip_type", "tipoViaje"})
    private String tripType;

    private String pickupAddress;       // Desde (location)
    private String dropoffAddress;      // Hasta (location)

    /** Nombre específico dentro de la location (transfer_point) */
    @JsonAlias({"pickupPointName", "pickup_point_name", "fromPointName"})
    private String pickupPointName;

    @JsonAlias({"dropoffPointName", "dropoff_point_name", "toPointName"})
    private String dropoffPointName;

    private String departureDate;   // yyyy-MM-dd
    private String departureTime;   // HH:mm

    private String returnDate;      // yyyy-MM-dd
    private String returnTime;      // HH:mm

    private String notes;

    /** Prestador del servicio */
    private String provider;

    @JsonAlias({"reservationCode", "codigoReserva", "codigo_reserva"})
    private String reservationCode;


    private String country;
    private String city;

    private String departureArrivalTime; // HH:mm
    private String returnArrivalTime;    // HH:mm


    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    @JsonAlias({"totalCosto"})
    private java.math.BigDecimal totalCost;

    private java.math.BigDecimal quotedValue;

}
