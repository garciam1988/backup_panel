package app.coincidir.api.domain.payment;

import app.coincidir.api.domain.GroupServiceMenuItem;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "service_payment_plan",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_service_payment_plan_menu_item",
                        columnNames = "menu_item_id"
                )
        }
)
@Getter
@Setter
public class ServicePaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "menu_item_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_service_payment_plan_menu_item")
    )
    private GroupServiceMenuItem menuItem;

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
    private List<ServicePaymentRecord> records = new ArrayList<>();
}
