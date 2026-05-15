package app.coincidir.api.domain.operations;

import app.coincidir.api.domain.TravelGroup;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Registro de operaciones por grupo (1:1 con TravelGroup).
 */
@Entity
@Table(
        name = "group_operations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_group_operations_group", columnNames = {"group_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupOperations {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "group_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_operations_group")
    )
    private TravelGroup group;

    @Column(name = "emitted_complete", nullable = false)
    private boolean emittedComplete = false;

    @Column(name = "emitted_complete_at")
    private Instant emittedCompleteAt;

    /**
     * Confirmación manual de que los servicios del grupo están completos.
     * Esto no reemplaza los estados de cada servicio; actúa como "check" de validación.
     */
    @Column(name = "services_complete", nullable = false)
    private boolean servicesComplete = false;

    @Column(name = "services_complete_at")
    private Instant servicesCompleteAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (emittedComplete && emittedCompleteAt == null) emittedCompleteAt = now;
        if (servicesComplete && servicesCompleteAt == null) servicesCompleteAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (emittedComplete && emittedCompleteAt == null) emittedCompleteAt = updatedAt;
        if (!emittedComplete) emittedCompleteAt = null;

        if (servicesComplete && servicesCompleteAt == null) servicesCompleteAt = updatedAt;
        if (!servicesComplete) servicesCompleteAt = null;
    }
}
