package app.coincidir.api.domain.expense;

import app.coincidir.api.domain.Prestador;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "expenses",
        indexes = {
                @Index(name = "idx_expenses_date", columnList = "expense_date"),
                @Index(name = "idx_expenses_type", columnList = "type"),
                @Index(name = "idx_expenses_status", columnList = "status"),
                @Index(name = "idx_expenses_provider", columnList = "provider_id"),
                @Index(name = "idx_expenses_amount", columnList = "amount"),
                @Index(name = "idx_expenses_menu_item_id", columnList = "menu_item_id"),
                @Index(name = "idx_expenses_service_payment_record_id", columnList = "service_payment_record_id")
        }
)
@Getter
@Setter
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optional: group associated to this expense (when applicable)
    @Column(name = "group_id")
    private Long groupId;

    // Optional: link to the menu item/service that generated this expense (when applicable)
    @Column(name = "menu_item_id")
    private Long menuItemId;

    // Optional: link to the service payment record that generated this expense (when applicable)
    @Column(name = "service_payment_record_id")
    private Long servicePaymentRecordId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private ExpenseType type;

    @Column(name = "category", length = 120)
    private String category;

    @Column(name = "concept", nullable = false, length = 255)
    private String concept;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", foreignKey = @ForeignKey(name = "fk_expenses_provider"))
    private Prestador provider;

    @Column(name = "payment_method", length = 60)
    private String paymentMethod;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "ARS";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    private ExpenseStatus status;

    @Column(name = "receipt_number", length = 80)
    private String receiptNumber;

    // Optional last 4 digits of the receipt/reference
    @Column(name = "receipt_last4", length = 4)
    private String receiptLast4;

    /**
     * Optional receipt attachment stored as binary (image/pdf/etc).
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "receipt_blob", columnDefinition = "LONGBLOB")
    private byte[] receiptBlob;

    @Column(name = "receipt_content_type", length = 255)
    private String receiptContentType;

    @Column(name = "receipt_file_name", length = 255)
    private String receiptFileName;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
 
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (currency == null || currency.isBlank()) currency = "ARS";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (currency == null || currency.isBlank()) currency = "ARS";
    }
}
