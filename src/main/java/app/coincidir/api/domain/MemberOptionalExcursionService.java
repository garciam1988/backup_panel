package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import app.coincidir.api.domain.payment.PaymentOneTimeMethod;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "member_optional_excursion_service",
        uniqueConstraints = {
                @UniqueConstraint(name="uq_member_optional_excursion", columnNames={"menu_item_id","member_id"})
        },
        indexes = {
                @Index(name="idx_member_optional_excursion_member", columnList="member_id"),
                @Index(name="idx_member_optional_excursion_excursion", columnList="excursion_id"),
                @Index(name="idx_member_optional_excursion_prestador", columnList="prestador_id")
        }
)
@Getter
@Setter
public class MemberOptionalExcursionService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="menu_item_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_excursion_menu_item"))
    private MemberOptionalServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="member_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_excursion_member"))
    private TravelRequest member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="excursion_id", foreignKey=@ForeignKey(name="fk_member_optional_excursion_catalog"))
    private ExcursionCatalog excursion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="prestador_id", foreignKey=@ForeignKey(name="fk_member_optional_excursion_prestador"))
    private Prestador prestador;

    @Column(name="name", nullable=false, length=160)
    private String name;

    @Column(name="excursion_date")
    private LocalDate excursionDate;

    @Column(name="excursion_time")
    private LocalTime excursionTime;

    @Column(name="excursion_return_time")
    private LocalTime excursionReturnTime;

    @Column(name="notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name="cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name="sale", precision = 12, scale = 2)
    private BigDecimal sale;

    @Enumerated(EnumType.STRING)
    @Column(name="payment_method", length = 30)
    private PaymentOneTimeMethod paymentMethod;

    @Column(name="provider", length = 160)
    private String provider;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt;

    @Column(name="updated_at", nullable=false)
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
