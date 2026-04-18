package app.coincidir.api.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "group_air_service")
public class GroupAirService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_air_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @Column(name = "trip_type", nullable = false, length = 20)
    private String tripType; // ONE_WAY | ROUND_TRIP

    @Column(name = "origin", length = 255)
    private String origin;

    @Column(name = "destination", length = 255)
    private String destination;


    @Column(name = "airline", nullable = false, length = 100)
    private String airline;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    @Column(name = "departure_arrival_time")
    private LocalTime departureArrivalTime;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "return_time")
    private LocalTime returnDepartureTime;

    @Column(name = "return_arrival_time")
    private LocalTime returnArrivalTime;

    // Guarda directamente "6_KG", "9_KG", etc.
    @Column(name = "baggage_allowance", nullable = false, length = 10)
    private String baggageAllowance;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "document_expiration_date")
    private LocalDate documentExpirationDate;

@Column(name = "quoted_value", precision = 12, scale = 4)
private java.math.BigDecimal quotedValue;

@Column(name = "quoted_at")
private java.time.Instant quotedAt;


    

@Column(name = "total_cost", precision = 12, scale = 2)
private java.math.BigDecimal totalCost;

@Column(name = "total_cost_updated_at")
private java.time.Instant totalCostUpdatedAt;
// Getters y setters

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public GroupServiceMenuItem getMenuItem() {
        return menuItem;
    }

    public void setMenuItem(GroupServiceMenuItem menuItem) {
        this.menuItem = menuItem;
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

    public String getReservationCode() {
        return reservationCode;
    }

    public void setReservationCode(String reservationCode) {
        this.reservationCode = reservationCode;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getTripType() { return tripType; }
    public void setTripType(String tripType) { this.tripType = tripType; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public LocalDate getDocumentExpirationDate() { return documentExpirationDate; }
    public void setDocumentExpirationDate(LocalDate documentExpirationDate) { this.documentExpirationDate = documentExpirationDate; }



public java.math.BigDecimal getQuotedValue() { return quotedValue; }
public void setQuotedValue(java.math.BigDecimal quotedValue) { this.quotedValue = quotedValue; }

public java.time.Instant getQuotedAt() { return quotedAt; }
public void setQuotedAt(java.time.Instant quotedAt) { this.quotedAt = quotedAt; }


public java.math.BigDecimal getTotalCost() { return totalCost; }
public void setTotalCost(java.math.BigDecimal totalCost) { this.totalCost = totalCost; }

public java.time.Instant getTotalCostUpdatedAt() { return totalCostUpdatedAt; }
public void setTotalCostUpdatedAt(java.time.Instant totalCostUpdatedAt) { this.totalCostUpdatedAt = totalCostUpdatedAt; }
}
