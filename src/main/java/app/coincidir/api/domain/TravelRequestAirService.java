package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Servicio Aéreos precargado a nivel Pasajero (TravelRequest).
 * Se copia como override al unirse a un grupo para evitar perder la precarga.
 */
@Entity
@Table(
        name = "travel_request_air_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_travel_request_air_service_request",
                        columnNames = {"request_id"}
                )
        },
        indexes = {
                @Index(name = "idx_travel_request_air_service_request", columnList = "request_id")
        }
)
@Getter
@Setter
public class TravelRequestAirService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "request_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_travel_request_air_service_request")
    )
    private TravelRequest request;

    @Column(name = "trip_type", nullable = false, length = 20)
    private String tripType;

    @Column(name = "origin")
    private String origin;

    @Column(name = "destination")
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

    @Column(name = "baggage_allowance", nullable = false, length = 10)
    private String baggageAllowance;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "document_expiration_date")
    private LocalDate documentExpirationDate;

    @Column(name = "quoted_value", precision = 12, scale = 4)
    private BigDecimal quotedValue;

    @Column(name = "quoted_at")
    private Instant quotedAt;

    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "total_cost_updated_at")
    private Instant totalCostUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
