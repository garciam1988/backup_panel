package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Catalogo de prestadores para Ferry.
 *
 * Nota: por ahora se usa para poblar combos en el frontend.
 * El servicio de Ferry sigue guardando el nombre (string) en ferryCompany.
 */
@Entity
@Table(
        name = "ferry_providers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ferry_providers_nombre", columnNames = {"nombre"})
        },
        indexes = {
                @Index(name = "idx_ferry_providers_activo", columnList = "activo")
        }
)
@Getter
@Setter
public class FerryProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Column(name = "direccion", length = 255)
    private String direccion;

    @Column(name = "telefono", length = 100)
    private String telefono;

    @Column(name = "web", length = 100)
    private String web;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
