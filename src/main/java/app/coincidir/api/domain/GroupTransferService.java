package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "group_transfer_service",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_group_transfer_service_menu_item", columnNames = "menu_item_id")
        }
)
@Getter
@Setter
public class GroupTransferService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_transfer_service_menu_item"))
    private GroupServiceMenuItem menuItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", length = 20)
    private TransferTripType tripType;

    // Desde
    @Column(name = "pickup_place")
    private String pickupPlace;

    // Nombre específico del punto de pickup (transfer_point)
    @Column(name = "pickup_point_name", length = 200)
    private String pickupPointName;

    // Hasta
    @Column(name = "destination_place")
    private String destinationPlace;

    // Nombre específico del punto de destino (transfer_point)
    @Column(name = "destination_point_name", length = 200)
    private String destinationPointName;

    @Column(name = "departure_date")
    private LocalDate departureDate;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "return_time")
    private LocalTime returnTime;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "provider", length = 160)
    private String provider;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "departure_arrival_time")
    private LocalTime departureArrivalTime;

    @Column(name = "return_arrival_time")
    private LocalTime returnArrivalTime;



@Column(name = "quoted_value", precision = 12, scale = 4)
private java.math.BigDecimal quotedValue;

@Column(name = "quoted_at")
private java.time.Instant quotedAt;



@Column(name = "total_cost", precision = 12, scale = 2)
private java.math.BigDecimal totalCost;

@Column(name = "total_cost_updated_at")
private java.time.Instant totalCostUpdatedAt;
}
