package app.coincidir.api.domain.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "member_payment_installment",
        indexes = {
                @Index(name = "idx_mpi_plan", columnList = "plan_id"),
                @Index(name = "idx_mpi_plan_num", columnList = "plan_id, installment_number", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberPaymentInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private MemberPaymentPlan plan;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;


    // Cobro de cuotas (Check Panel): marca cuando se notificó la cuota
    @Column(name = "collection_notified_at")
    private LocalDateTime collectionNotifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private InstallmentStatus status = InstallmentStatus.PLANNED;
}
