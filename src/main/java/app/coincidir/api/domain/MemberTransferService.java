// src/main/java/app/coincidir/api/domain/MemberTransferService.java
package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "member_transfer_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_member_transfer_service_menu_item_member",
                        columnNames = {"menu_item_id", "member_id"}
                )
        },
        indexes = {
                @Index(name = "idx_member_transfer_member", columnList = "member_id"),
                @Index(name = "idx_member_transfer_menu_item", columnList = "menu_item_id")
        }
)
@Getter
@Setter
public class MemberTransferService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_transfer_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_transfer_service_member")
    )
    private TravelRequest member;

    @Column(name = "overridden", nullable = false)
    private boolean overridden = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", length = 20)
    private TransferTripType tripType;

    // Desde
    @Column(name = "pickup_place")
    private String pickupPlace;

    @Column(name = "pickup_point_name", length = 200)
    private String pickupPointName;

    // Hasta
    @Column(name = "destination_place")
    private String destinationPlace;

    @Column(name = "destination_point_name", length = 200)
    private String destinationPointName;

    @Column(name = "departure_date")
    private LocalDate departureDate;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    @Column(name = "departure_arrival_time")
    private LocalTime departureArrivalTime;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "return_time")
    private LocalTime returnTime;

    @Column(name = "return_arrival_time")
    private LocalTime returnArrivalTime;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "provider", length = 160)
    private String provider;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

@Column(name = "quoted_value", precision = 12, scale = 4)
private java.math.BigDecimal quotedValue;

@Column(name = "quoted_at")
private java.time.Instant quotedAt;


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
