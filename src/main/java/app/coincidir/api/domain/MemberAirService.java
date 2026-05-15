// src/main/java/app/coincidir/api/domain/MemberAirService.java
package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "member_air_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_member_air_service_menu_item_member",
                        columnNames = {"menu_item_id", "member_id"}
                )
        },
        indexes = {
                @Index(name = "idx_member_air_member", columnList = "member_id"),
                @Index(name = "idx_member_air_menu_item", columnList = "menu_item_id")
        }
)
@Getter
@Setter
public class MemberAirService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_air_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_air_service_member")
    )
    private TravelRequest member;

    @Column(name = "overridden", nullable = false)
    private boolean overridden = false;

    // ONE_WAY | ROUND_TRIP (mismo formato que GroupAirService)
    @Column(name = "trip_type", nullable = false, length = 20)
    private String tripType;

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

    // "6_KG", "9_KG", etc (mismo formato que GroupAirService)
    @Column(name = "baggage_allowance", nullable = false, length = 10)
    private String baggageAllowance;

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
