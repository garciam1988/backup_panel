package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Coupon — Cupón de descuento canjeable en el local.
 *
 * A diferencia de LoyaltyReward (que se obtiene gastando stamps/puntos),
 * un cupón es un descuento directo que el cliente recibe (por campaña,
 * cumpleaños, welcome, o cargado manualmente).
 *
 * Tipos de descuento (discountType):
 *   - PERCENTAGE  Porcentaje (discount_value = 20 para 20%)
 *   - FIXED       Monto fijo (discount_value = 500)
 *   - FREE_ITEM   Item gratis (free_item_ref = "Roll California")
 *   - BOGO        Buy-one-get-one
 *
 * Tipos de uso (usageType):
 *   - SINGLE_USE_GLOBAL        Uno solo lo usa, primero que llega
 *   - SINGLE_USE_PER_CUSTOMER  Cada cliente 1 vez
 *   - MULTI_USE_PER_CUSTOMER   Cada cliente N veces
 */
@Entity
@Table(name = "coupon")
@Getter @Setter
public class Coupon {

    public enum DiscountType { PERCENTAGE, FIXED, FREE_ITEM, BOGO }
    public enum UsageType { SINGLE_USE_GLOBAL, SINGLE_USE_PER_CUSTOMER, MULTI_USE_PER_CUSTOMER }
    public enum Source { MANUAL, CAMPAIGN, BIRTHDAY_AUTO, REFERRAL_AUTO, WELCOME_AUTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código alfanumérico único. Ej: "WELCOME20", "ROLL2X1". */
    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    /** Para PERCENTAGE: 0-100. Para FIXED: monto. Para FREE_ITEM/BOGO: null. */
    @Column(name = "discount_value", precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "free_item_ref", length = 150)
    private String freeItemRef;

    @Column(name = "min_purchase", precision = 12, scale = 2)
    private BigDecimal minPurchase;

    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "valid_days_of_week_json", columnDefinition = "JSON")
    private String validDaysOfWeekJson;

    @Column(name = "valid_branches_json", columnDefinition = "JSON")
    private String validBranchesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, length = 30)
    private UsageType usageType = UsageType.MULTI_USE_PER_CUSTOMER;

    /** NULL = ilimitado a nivel global. */
    @Column(name = "max_uses_total")
    private Integer maxUsesTotal;

    @Column(name = "max_uses_per_customer", nullable = false)
    private Integer maxUsesPerCustomer = 1;

    @Column(name = "current_uses", nullable = false)
    private Integer currentUses = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private Source source = Source.MANUAL;

    @Column(name = "campaign_id")
    private Long campaignId;

    /**
     * Si NO es null, el cupón solo se muestra a clientes que matcheen el
     * segmento indicado (evaluado por SegmentEvaluator). Si es null, el
     * cupón aplica a TODOS los clientes (comportamiento histórico).
     *
     * No usamos FK en BD a propósito: si el admin borra el segmento, los
     * cupones que lo referencian quedan "huérfanos" y se tratan como si
     * segmentId fuera null (visibles a todos). Es preferible eso a romper
     * promociones en producción.
     */
    @Column(name = "segment_id")
    private Long segmentId;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /**
     * Si NO es null, el cupón está archivado: existe en BD para preservar el
     * histórico de coupon_use, pero NO aparece en la lista del panel admin
     * (salvo que se pida includeArchived=true) y se fuerza active=false al
     * archivarlo. Es la alternativa al hard delete cuando ya tuvo usos.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

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
