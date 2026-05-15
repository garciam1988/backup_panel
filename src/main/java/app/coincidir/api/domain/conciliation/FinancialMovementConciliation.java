package app.coincidir.api.domain.conciliation;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "financial_movement_conciliation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_fmc_type_movement", columnNames = {"movement_type", "movement_id"})
        },
        indexes = {
                @Index(name = "idx_fmc_type_movement", columnList = "movement_type, movement_id"),
                @Index(name = "idx_fmc_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialMovementConciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 64)
    private FinancialMovementType movementType;

    @Column(name = "movement_id", nullable = false)
    private Long movementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConciliationStatus status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // Nro de comprobante bancario ingresado al verificar
    @Column(name = "bank_receipt_number", length = 80)
    private String bankReceiptNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = ConciliationStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
