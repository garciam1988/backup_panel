package app.coincidir.api.domain;

import app.coincidir.api.domain.operations.OperationStatusCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ítem del menú de servicios dentro de un grupo (instancia).
 * Permite duplicados (ej: 2 Ferrys) y orden persistido.
 */
@Entity
@Table(
        name = "group_service_menu_item",
        indexes = {
                @Index(name = "idx_gsmi_group_pos", columnList = "group_id,position"),
                @Index(name = "idx_gsmi_group_service", columnList = "group_id,service_id")
        }
)
@Getter
@Setter
public class GroupServiceMenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "group_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsmi_group")
    )
    private TravelGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "service_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsmi_service")
    )
    private ServiceDefinition service;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "position", nullable = false)
    private Integer position;

    /** Cotización grupal (valor total, en USD). Se guarda desde el modal de Cotización. */
    @Column(name = "quoted_value", precision = 14, scale = 4)
    private BigDecimal quotedValue;

    /** Texto libre — usado por el servicio ADICIONALES para describir qué se vendió. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // Estado operativo del ítem (usado por OperationsService)
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_status", length = 40)
    private OperationStatusCode operationStatus;

    @Column(name = "operation_status_updated_at")
    private Instant operationStatusUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
