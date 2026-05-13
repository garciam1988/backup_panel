package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * CouponUse — Cada vez que un cupón se aplica en una compra real.
 *
 * Inmutable. Si hay que revertir un uso (cliente devolvió la compra), se
 * crea otro CouponUse con discountApplied negativo en lugar de borrar.
 * Eso mantiene la auditoría intacta.
 *
 * El campo current_uses de Coupon se incrementa en cada insert acá. El
 * servicio CouponService valida antes de insertar:
 *   - El cupón está activo y dentro de su vigencia
 *   - No se excedió max_uses_total
 *   - No se excedió max_uses_per_customer para este customer
 *   - Si tiene min_purchase, el monto cumple
 *   - Si tiene branch_restrictions, el branch coincide
 *   - Si tiene days_of_week, el día actual coincide
 */
@Entity
@Table(name = "coupon_use")
@Getter @Setter
public class CouponUse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    @Column(name = "used_branch", length = 64)
    private String usedBranch;

    @Column(name = "used_by_user", length = 64)
    private String usedByUser;

    @Column(name = "purchase_amount", precision = 12, scale = 2)
    private BigDecimal purchaseAmount;

    @Column(name = "discount_applied", precision = 12, scale = 2)
    private BigDecimal discountApplied;

    @PrePersist
    void onCreate() {
        if (usedAt == null) usedAt = Instant.now();
    }
}
