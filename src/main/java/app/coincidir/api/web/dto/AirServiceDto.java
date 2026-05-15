package app.coincidir.api.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.LocalDate;
import java.time.LocalTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AirServiceDto {

    private Long id;
    private Long groupId;

    private java.math.BigDecimal quotedValue;
    private String quotedAt;
    @JsonAlias({"totalCosto"})
    private java.math.BigDecimal totalCost;
    private String totalCostUpdatedAt;

    private String airline;

    private LocalDate departureDate;
    private LocalTime departureTime;
    private LocalTime departureArrivalTime;

    private LocalDate returnDate;
    private LocalTime returnDepartureTime;
    private LocalTime returnArrivalTime;

    // "6_KG", "9_KG", "10_KG", etc.
    private String baggageAllowance;

    private String tripType;     // ONE_WAY | ROUND_TRIP
    private String origin;
    private String destination;

    @JsonAlias({"reservationCode", "reservation_code", "codigoReserva", "codigo_reserva", "codigoDeReserva"})
    private String reservationCode;

    @JsonAlias({"documentExpirationDate", "document_expiration_date", "vencimientoDocumento", "fechaVencimientoDocumento"})
    private LocalDate documentExpirationDate;

    @JsonAlias({"notes", "notas", "nota", "observaciones", "observacion"})
    private String notes;

    public String getTripType() { return tripType; }
    public void setTripType(String tripType) { this.tripType = tripType; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getReservationCode() { return reservationCode; }
    public void setReservationCode(String reservationCode) { this.reservationCode = reservationCode; }

    public LocalDate getDocumentExpirationDate() { return documentExpirationDate; }
    public void setDocumentExpirationDate(LocalDate documentExpirationDate) { this.documentExpirationDate = documentExpirationDate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getAirline() {
        return airline;
    }

    public void setAirline(String airline) {
        this.airline = airline;
    }

    public LocalDate getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(LocalDate departureDate) {
        this.departureDate = departureDate;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public LocalTime getDepartureArrivalTime() {
        return departureArrivalTime;
    }

    public void setDepartureArrivalTime(LocalTime departureArrivalTime) {
        this.departureArrivalTime = departureArrivalTime;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(LocalDate returnDate) {
        this.returnDate = returnDate;
    }

    public LocalTime getReturnDepartureTime() {
        return returnDepartureTime;
    }

    public void setReturnDepartureTime(LocalTime returnDepartureTime) {
        this.returnDepartureTime = returnDepartureTime;
    }

    public LocalTime getReturnArrivalTime() {
        return returnArrivalTime;
    }

    public void setReturnArrivalTime(LocalTime returnArrivalTime) {
        this.returnArrivalTime = returnArrivalTime;
    }

    public String getBaggageAllowance() {
        return baggageAllowance;
    }

    public void setBaggageAllowance(String baggageAllowance) {
        this.baggageAllowance = baggageAllowance;
    }


public java.math.BigDecimal getQuotedValue() { return quotedValue; }
public void setQuotedValue(java.math.BigDecimal quotedValue) { this.quotedValue = quotedValue; }

public String getQuotedAt() { return quotedAt; }
public void setQuotedAt(String quotedAt) { this.quotedAt = quotedAt; }


public java.math.BigDecimal getTotalCost() { return totalCost; }
public void setTotalCost(java.math.BigDecimal totalCost) { this.totalCost = totalCost; }

public String getTotalCostUpdatedAt() { return totalCostUpdatedAt; }
public void setTotalCostUpdatedAt(String totalCostUpdatedAt) { this.totalCostUpdatedAt = totalCostUpdatedAt; }
}
