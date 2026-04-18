package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "member_optional_luggage_service",
        uniqueConstraints = {
                @UniqueConstraint(name="uq_member_optional_luggage", columnNames={"menu_item_id","member_id"})
        },
        indexes = {
                @Index(name="idx_member_optional_luggage_member", columnList="member_id")
        }
)
@Getter
@Setter
public class MemberOptionalLuggageService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="menu_item_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_luggage_menu_item"))
    private MemberOptionalServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="member_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_luggage_member"))
    private TravelRequest member;

    @Enumerated(EnumType.STRING)
    @Column(name="luggage_type", nullable=false, length=20)
    private OptionalLuggageType type;

    @Column(name="weight_kg", precision = 12, scale = 2)
    private BigDecimal weightKg;

    // Aerolínea seleccionada (nombre). Se guarda para que al reabrir el modal quede preseleccionada.
    @Column(name = "airline", length = 120)
    private String airline;

    // Regla seleccionada (tabla airline_baggage_rule). Guardamos el id para mantener la relación.
    @Column(name = "baggage_rule_id")
    private Long baggageRuleId;

    // Dimensiones asociadas al peso/regla seleccionada.
    @Column(name = "dimensions", length = 128)
    private String dimensions;

    @Column(name="notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name="cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name="sale", precision = 12, scale = 2)
    private BigDecimal sale;

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
