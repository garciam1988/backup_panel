package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyRedemption — Canje de un premio.
 *
 * Flujo típico:
 *   1. Cliente desde la PWA toca "Canjear" en un reward.
 *   2. Backend valida (stamps suficientes, restricciones, stock) y crea un
 *      LoyaltyRedemption con status=PENDING, genera redemption_code (6-8
 *      chars alfanuméricos, único) y expires_at (ej: 24hs).
 *   3. Al mismo tiempo se crea un LoyaltyTransaction tipo redeem_reward con
 *      los deltas negativos, decrementando los balances de la tarjeta.
 *   4. El cliente muestra el redemption_code al mozo en el local.
 *   5. El mozo en el panel de staff ingresa/escanea el código, lo valida,
 *      y el sistema marca el redemption como REDEEMED.
 *   6. Si expires_at pasa sin uso, un cron job lo marca EXPIRED y se crea
 *      una transaction de adjustment que REVIERTE los deltas (devuelve el
 *      stamp al cliente). Importante: el cliente no pierde el premio si no
 *      llegó a usarlo.
 */
@Entity
@Table(name = "loyalty_redemption")
@Getter @Setter
public class LoyaltyRedemption {

    public enum Status {
        PENDING, REDEEMED, EXPIRED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "reward_id", nullable = false)
    private Long rewardId;

    /**
     * Código único para que el mozo valide. 6-8 caracteres alfanuméricos
     * sin caracteres ambiguos (sin 0/O ni 1/l).
     */
    @Column(name = "redemption_code", nullable = false, length = 20, unique = true)
    private String redemptionCode;

    @Column(name = "stamps_cost", nullable = false)
    private Integer stampsCost = 0;

    @Column(name = "points_cost", nullable = false)
    private Integer pointsCost = 0;

    @Column(name = "cashback_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashbackCost = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "redeemed_branch", length = 64)
    private String redeemedBranch;

    @Column(name = "redeemed_by_user", length = 64)
    private String redeemedByUser;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @PrePersist
    void onCreate() {
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
