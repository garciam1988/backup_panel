package app.coincidir.api.domain.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Single payment event (used for "Últimos pagos" in Admin Panel).
 *
 * For ONE_TIME plans, installmentNumber is always 1.
 */
@Entity
@Table(
        name = "member_payment_record",
        indexes = {
                @Index(name = "idx_mpr_group_member", columnList = "group_id, member_id"),
                @Index(name = "idx_mpr_plan", columnList = "plan_id"),
                @Index(name = "idx_mpr_group", columnList = "group_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberPaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private MemberPaymentPlan plan;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /**
     * For OWN_FINANCING: installment number paid.
     * For ONE_TIME: always 1.
     */
    @Column(name = "installment_number")
    private Integer installmentNumber;

    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "receipt_last4", length = 4, nullable = false)
    private String receiptLast4;

    /**
     * Optional payment receipt (image/pdf/etc) stored as binary.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    // Force MySQL to use a large binary type; default @Lob mapping may create BLOB (64KB) which is too small.
    @Column(name = "receipt_blob", columnDefinition = "LONGBLOB")
    private byte[] receiptBlob;

    @Column(name = "receipt_content_type", length = 255)
    private String receiptContentType;

    @Column(name = "receipt_file_name", length = 255)
    private String receiptFileName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (currency == null || currency.isBlank()) currency = "ARS";
    }
}
