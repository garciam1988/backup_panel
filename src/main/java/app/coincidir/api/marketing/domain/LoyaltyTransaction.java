package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyTransaction — Movimiento sobre una tarjeta. INMUTABLE.
 *
 * Toda operación que suma o resta stamps/puntos/cashback queda registrada acá.
 * Los balances de loyalty_card son una cache calculable a partir de esta tabla.
 *
 * Nunca update, nunca delete. Cuando hay un bug o se necesita revertir algo,
 * se inserta un transaction de tipo 'adjustment' con los deltas inversos. El
 * original NUNCA se borra — eso garantiza auditabilidad total.
 *
 * Tipos de transacción (transactionType):
 *   - earn_stamps       Suma stamps
 *   - earn_points       Suma puntos según monto de compra
 *   - earn_cashback     Suma cashback según monto de compra
 *   - redeem_reward     Resta al canjear premio
 *   - expire            Resta puntos/cashback por vencimiento (job nightly)
 *   - adjustment        Ajuste manual del admin (con notes obligatorio)
 *   - refund            Devolución (compra revertida)
 *
 * Origen (source):
 *   - staff_scan, customer_qr, bot, admin_manual,
 *   - auto_reservation, auto_birthday, import, system
 */
@Entity
@Table(name = "loyalty_transaction")
@Getter @Setter
public class LoyaltyTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    @Column(name = "stamps_delta", nullable = false)
    private Integer stampsDelta = 0;

    @Column(name = "points_delta", nullable = false)
    private Integer pointsDelta = 0;

    @Column(name = "cashback_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashbackDelta = BigDecimal.ZERO;

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "purchase_amount", precision = 12, scale = 2)
    private BigDecimal purchaseAmount;

    @Column(name = "reservation_table_slug", length = 60)
    private String reservationTableSlug;

    @Column(name = "reservation_record_id")
    private Long reservationRecordId;

    @Column(name = "reward_id")
    private Long rewardId;

    @Column(name = "redemption_id")
    private Long redemptionId;

    /**
     * Snapshot de las reglas aplicadas (ej: "doble stamp por miércoles").
     * JSON array. Útil para auditar y para mostrar al cliente
     * "ganaste 2 stamps porque era miércoles".
     */
    @Column(name = "applied_rules_json", columnDefinition = "JSON")
    private String appliedRulesJson;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "performed_by", length = 64)
    private String performedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
