package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyCard — Estado actual de la tarjeta de un cliente.
 *
 * Una fila por cliente (UNIQUE customer_id). Es una CACHE calculable: los
 * balances actuales y de lifetime se reconstruyen sumando loyalty_transaction.
 * La regla de oro es: si hay discrepancia entre esta tabla y la suma de
 * transactions, gana las transactions y se recalcula.
 *
 * Diferencia entre current_* y lifetime_*:
 *   - current_stamps:  stamps que el cliente PUEDE usar ahora. Se resetean
 *                      al canjear (si stampsResetOnRedeem=true) o decrementan.
 *   - lifetime_stamps: total acumulado de stamps ganados en toda su historia.
 *                      Nunca decrementa. Sirve para tier progression y
 *                      reportes de engagement.
 */
@Entity
@Table(name = "loyalty_card")
@Getter @Setter
public class LoyaltyCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "current_stamps", nullable = false)
    private Integer currentStamps = 0;

    @Column(name = "current_points", nullable = false)
    private Integer currentPoints = 0;

    @Column(name = "cashback_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashbackBalance = BigDecimal.ZERO;

    @Column(name = "lifetime_stamps", nullable = false)
    private Integer lifetimeStamps = 0;

    @Column(name = "lifetime_points", nullable = false)
    private Integer lifetimePoints = 0;

    @Column(name = "lifetime_cashback", nullable = false, precision = 12, scale = 2)
    private BigDecimal lifetimeCashback = BigDecimal.ZERO;

    /** Código del tier actual ("BRONZE","SILVER","GOLD"). NULL = sin tier. */
    @Column(name = "tier_code", length = 30)
    private String tierCode;

    /** Progreso dentro del tier (0-100 o monto absoluto, según se defina). */
    @Column(name = "tier_progress", precision = 10, scale = 2)
    private BigDecimal tierProgress;

    @Column(name = "last_stamp_at")
    private Instant lastStampAt;

    @Column(name = "last_redeem_at")
    private Instant lastRedeemAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
