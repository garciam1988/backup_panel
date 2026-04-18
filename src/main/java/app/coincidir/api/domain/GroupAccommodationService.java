package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "group_accommodation_service",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_group_accommodation_service_menu_item",
                        columnNames = "menu_item_id"
                )
        }
)
@Getter
@Setter
public class GroupAccommodationService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_accommodation_service_menu_item")
    )
    private GroupServiceMenuItem menuItem;

    @Column(name = "name", length = 200, nullable = false)
    private String name;  // nombre del alojamiento

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

    @Column(name = "reservation_amount", precision = 12, scale = 2)
    private java.math.BigDecimal reservationAmount;


@Column(name = "quoted_value", precision = 12, scale = 4)
private java.math.BigDecimal quotedValue;

@Column(name = "quoted_at")
private java.time.Instant quotedAt;



@Column(name = "total_cost", precision = 12, scale = 2)
private java.math.BigDecimal totalCost;

@Column(name = "total_cost_updated_at")
private java.time.Instant totalCostUpdatedAt;

    /**
     * Fecha de vencimiento de la reserva (operaciones).
     */
    @Column(name = "reservation_due_date")
    private LocalDate reservationDueDate;
}
