package app.coincidir.api.domain.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "service_payment_record",
        indexes = {
                @Index(name = "idx_service_payment_record_plan", columnList = "plan_id")
        }
)
@Getter
@Setter
public class ServicePaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "plan_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_service_payment_record_plan")
    )
    private ServicePaymentPlan plan;

    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;


    @Column(name = "total_payment_cancellation_date")
    private LocalDate totalPaymentCancellationDate;

    @Column(name = "one_time_method", length = 80)
    private String oneTimeMethod;

    @Column(name = "receipt_last4", length = 4)
    private String receiptLast4;

    @Column(name = "receipt_number", length = 80)
    private String receiptNumber;

    @Column(name = "reservation_code", length = 100)
    private String reservationCode;

    @Column(name = "flight_number", length = 80)
    private String flightNumber;

    // AÉREOS: asociar pago a un pasajero puntual (para mostrar en UI)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "passenger_full_name", length = 255)
    private String passengerFullName;

    // AÉREOS: ID de operación (registro único en expenses) asociado al pago
    @Column(name = "expense_id")
    private Long expenseId;


    @Column(name = "bank_id")
    private Long bankId;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "card_number", length = 32)
    private String cardNumber;

    /* Optional receipt file (image/pdf) */
    @Lob
    @Column(name = "receipt_blob", columnDefinition = "LONGBLOB")
    private byte[] receiptBlob;

    @Column(name = "receipt_content_type", length = 255)
    private String receiptContentType;

    @Column(name = "receipt_file_name", length = 255)
    private String receiptFileName;


    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
