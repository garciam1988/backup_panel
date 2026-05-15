package app.coincidir.api.domain.operations;

import app.coincidir.api.domain.ServiceCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Parámetros de estados disponibles por tipo de servicio (ServiceCode).
 */
@Entity
@Table(
        name = "service_operation_status_def",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_sosd_service_status", columnNames = {"service_code", "status_code"})
        },
        indexes = {
                @Index(name = "idx_sosd_service", columnList = "service_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceOperationStatusDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_code", nullable = false, length = 50)
    private ServiceCode serviceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_code", nullable = false, length = 50)
    private OperationStatusCode statusCode;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", nullable = false, length = 20)
    private ServiceStatusColor color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (sortOrder == null) sortOrder = 0;
    }
}
