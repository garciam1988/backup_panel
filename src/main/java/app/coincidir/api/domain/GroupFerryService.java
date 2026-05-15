package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "group_ferry_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_group_ferry_service_menu_item",
                        columnNames = "menu_item_id"
                )
        }
)
@Getter
@Setter
public class GroupFerryService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_ferry_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", length = 20, nullable = false)
    private FerryTripType tripType;

    @Column(name = "departure_time")
    private LocalTime departureTime;   // hora ida

    @Column(name = "return_time")
    private LocalTime returnTime;      // hora vuelta (puede ser null)

    @Column(name = "origin_port", length = 100, nullable = false)
    private String originPort;

    @Column(name = "destination_port", length = 100, nullable = false)
    private String destinationPort;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "ferry_company", length = 100)
    private String ferryCompany;

    @Column(name = "provider", length = 160)
    private String provider;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "departure_arrival_time")
    private LocalTime departureArrivalTime;

    @Column(name = "return_arrival_time")
    private LocalTime returnArrivalTime;

    // --- Bus fields ---
    @Column(name = "bus_origin", length = 160)
    private String busOrigin;

    @Column(name = "bus_destination", length = 160)
    private String busDestination;

    @Column(name = "bus_departure_time")
    private LocalTime busDepartureTime;

    @Column(name = "bus_arrival_time")
    private LocalTime busArrivalTime;

    @Column(name = "return_bus_departure_time")
    private LocalTime returnBusDepartureTime;

    @Column(name = "return_bus_arrival_time")
    private LocalTime returnBusArrivalTime;

@Column(name = "quoted_value", precision = 12, scale = 4)
private java.math.BigDecimal quotedValue;

@Column(name = "quoted_at")
private java.time.Instant quotedAt;



    

@Column(name = "total_cost", precision = 12, scale = 2)
private java.math.BigDecimal totalCost;

@Column(name = "total_cost_updated_at")
private java.time.Instant totalCostUpdatedAt;
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
