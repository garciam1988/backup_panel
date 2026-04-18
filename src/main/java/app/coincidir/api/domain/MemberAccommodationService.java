// src/main/java/app/coincidir/api/domain/MemberAccommodationService.java
package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "member_accommodation_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_member_accommodation_service_menu_item_member",
                        columnNames = {"menu_item_id", "member_id"}
                )
        },
        indexes = {
                @Index(name = "idx_member_accommodation_member", columnList = "member_id"),
                @Index(name = "idx_member_accommodation_menu_item", columnList = "menu_item_id")
        }
)
@Getter
@Setter
public class MemberAccommodationService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_accommodation_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_accommodation_service_member")
    )
    private TravelRequest member;

    @Column(name = "overridden", nullable = false)
    private boolean overridden = false;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_in_time")
    private LocalTime checkInTime;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(name = "check_out_time")
    private LocalTime checkOutTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "regimen", length = 30, nullable = false)
    private AccommodationRegimen regimen;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "city", length = 150)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", length = 20, nullable = false)
    private AccommodationContractType contractType = AccommodationContractType.DIRECTA;

    @Column(name = "third_party_name", length = 200)
    private String thirdPartyName;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "accommodation_id")
    private Long accommodationId;

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
