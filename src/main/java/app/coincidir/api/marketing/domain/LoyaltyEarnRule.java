package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyEarnRule — Regla dinámica que modifica el cálculo de earn.
 *
 * Cuando el sistema procesa un earn (purchase, enrollment, birthday, etc),
 * busca todas las reglas activas que aplican al contexto y las evalúa
 * en orden de priority (DESC). Cada regla puede:
 *
 *   - MULTIPLIER: multiplica el valor target por multiplierValue
 *     (ej. martes 2x estampillas → multiplierValue=2.0, target=STAMPS)
 *
 *   - BONUS: suma valor fijo al target
 *     (ej. cliente nuevo +1 estampilla → bonusStamps=1, target=STAMPS)
 *
 * Las reglas ACUMULAN: si dos reglas aplican al mismo target, se aplican
 * ambas (primero todas las BONUS, después todas los MULTIPLIER, en orden
 * de priority). Esto da resultados predecibles tipo "+1 base, +1 cumple,
 * todo x2 por martes = (1+1)*2 = 4".
 *
 * conditions_json define cuándo aplica la regla. Estructura esperada:
 *   {
 *     "trigger":      "purchase" | "enrollment" | "birthday",
 *     "daysOfWeek":   [1..7] (lunes=1, domingo=7) — opcional,
 *     "branchIds":    ["centro", "palermo"] — opcional,
 *     "minPurchase":  numérico — opcional,
 *     "maxPurchase":  numérico — opcional
 *   }
 * Si un campo no está, no filtra. Múltiples campos son AND entre sí.
 */
@Getter
@Setter
@Entity
@Table(name = "loyalty_earn_rule")
public class LoyaltyEarnRule {

    public enum RuleType { MULTIPLIER, BONUS }
    public enum Target   { STAMPS, POINTS, CASHBACK, ALL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private RuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Target target;

    /** Para MULTIPLIER: el factor (2.0 = duplica, 1.5 = +50%). */
    @Column(name = "multiplier_value", precision = 6, scale = 2)
    private BigDecimal multiplierValue;

    /** Para BONUS de tipo STAMPS o ALL. */
    @Column(name = "bonus_stamps")
    private Integer bonusStamps;

    /** Para BONUS de tipo POINTS o ALL. */
    @Column(name = "bonus_points")
    private Integer bonusPoints;

    /** Para BONUS de tipo CASHBACK o ALL. */
    @Column(name = "bonus_cashback", precision = 10, scale = 2)
    private BigDecimal bonusCashback;

    /** JSON con las condiciones de aplicación. Ver javadoc de la clase. */
    @Column(name = "conditions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsJson;

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (priority == null) priority = 0;
        if (active == null) active = true;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
