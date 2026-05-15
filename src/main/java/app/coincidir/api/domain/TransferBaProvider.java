package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "transfer_ba_providers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_transfer_ba_providers_nombre", columnNames = {"nombre"})
        },
        indexes = {
                @Index(name = "idx_transfer_ba_providers_activo", columnList = "activo")
        }
)
@Getter
@Setter
public class TransferBaProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "telefono", length = 60)
    private String telefono;

    @Column(name = "web", length = 255)
    private String web;

    // nullable = true para tolerar registros legacy con fecha cero en DB
    @Column(name = "created_at", nullable = true, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = true)
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
