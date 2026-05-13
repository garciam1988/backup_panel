package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyReward — Premio canjeable del catálogo.
 *
 * Un premio define cuánto cuesta (en stamps, puntos o cashback) y opcionalmente
 * cuándo y dónde se puede canjear. El admin edita este catálogo desde
 * /marketing > Recompensas.
 *
 * Tipos (rewardType):
 *   - STAMPS:   costo en stamps (campo costStamps obligatorio)
 *   - POINTS:   costo en puntos (campo costPoints obligatorio)
 *   - CASHBACK: costo en cashback (campo costCashback obligatorio)
 *   - FREE:     gratis, sin costo. Se usa para premios de campaña / cumpleaños /
 *               welcome. El cliente lo "canjea" pero no se le resta nada.
 *
 * Stock: stockTotal NULL = ilimitado. stockRemaining se decrementa en cada
 * canje confirmado. maxPerCustomer limita cuántas veces un mismo cliente
 * puede canjear el mismo premio (default NULL = sin límite).
 */
@Entity
@Table(name = "loyalty_reward")
@Getter @Setter
public class LoyaltyReward {

    public enum RewardType {
        STAMPS, POINTS, CASHBACK,
        /** Premio gratuito (campañas, cumpleaños, welcome). Sin costo. */
        FREE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, length = 20)
    private RewardType rewardType;

    @Column(name = "cost_stamps")
    private Integer costStamps;

    @Column(name = "cost_points")
    private Integer costPoints;

    @Column(name = "cost_cashback", precision = 12, scale = 2)
    private BigDecimal costCashback;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /** NULL = ilimitado. */
    @Column(name = "stock_total")
    private Integer stockTotal;

    @Column(name = "stock_remaining")
    private Integer stockRemaining;

    /** Cuántas veces puede canjear este premio un mismo cliente. NULL = sin límite. */
    @Column(name = "max_per_customer")
    private Integer maxPerCustomer;

    /** JSON array: ["MON","WED","FRI"]. NULL = todos los días. */
    @Column(name = "valid_days_of_week", columnDefinition = "JSON")
    private String validDaysOfWeek;

    /** JSON: {"from":"20:00","to":"23:00"}. NULL = todo el día. */
    @Column(name = "valid_hours_json", columnDefinition = "JSON")
    private String validHoursJson;

    /** JSON array de branchIds permitidos. NULL = todos los locales. */
    @Column(name = "branch_restrictions", columnDefinition = "JSON")
    private String branchRestrictions;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Orden de visualización en la lista de premios de la PWA. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
