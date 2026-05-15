package app.coincidir.api.domain.payment;

import app.coincidir.api.domain.MemberOptionalServiceMenuItem;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "member_optional_service_payment_plan",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_mosp_plan_menu_item",
                        columnNames = "menu_item_id"
                )
        }
)
@Getter
@Setter
public class OptionalServicePaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_mosp_plan_menu_item")
    )
    private MemberOptionalServiceMenuItem menuItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_form", length = 20, nullable = false)
    private ServicePaymentForm paymentForm;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("paymentDate asc, id asc")
    private List<OptionalServicePaymentRecord> records = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
