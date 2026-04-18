package app.coincidir.api.domain.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "member_optional_service_payment_record",
        indexes = {
                @Index(name = "idx_mosp_record_plan", columnList = "plan_id")
        }
)
@Getter
@Setter
public class OptionalServicePaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "plan_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_mosp_record_plan")
    )
    private OptionalServicePaymentPlan plan;

    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "one_time_method", length = 80)
    private String oneTimeMethod;

    @Column(name = "receipt_last4", length = 4)
    private String receiptLast4;

    @Column(name = "receipt_number", length = 80)
    private String receiptNumber;

    @Column(name = "bank_id")
    private Long bankId;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "card_number", length = 32)
    private String cardNumber;

    @Lob
    @Column(name = "receipt_blob", columnDefinition = "LONGBLOB")
    private byte[] receiptBlob;

    @Column(name = "receipt_content_type", length = 255)
    private String receiptContentType;

    @Column(name = "receipt_file_name", length = 255)
    private String receiptFileName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }
}
