package app.coincidir.api.domain.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "member_payment_plan",
        indexes = {
                @Index(name = "idx_mpp_group_member", columnList = "group_id, member_id", unique = true),
                @Index(name = "idx_mpp_group", columnList = "group_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberPaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 32)
    private PaymentPlanType planType;

    @Enumerated(EnumType.STRING)
    @Column(name = "one_time_method", length = 32)
    private PaymentOneTimeMethod oneTimeMethod;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    @Builder.Default
    private String currency = "ARS";

    @Column(name = "receipt_last4", length = 4)
    private String receiptLast4;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNumber ASC")
    private List<MemberPaymentInstallment> installments = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (currency == null || currency.isBlank()) currency = "ARS";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (currency == null || currency.isBlank()) currency = "ARS";
    }
}
