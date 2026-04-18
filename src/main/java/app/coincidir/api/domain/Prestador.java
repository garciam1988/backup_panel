package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "prestadores",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_prestadores_nombre", columnNames = {"nombre"})
        },
        indexes = {
                @Index(name = "idx_prestadores_activo", columnList = "activo")
        }
)
@Getter
@Setter
public class Prestador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @ManyToMany(mappedBy = "prestadores", fetch = FetchType.LAZY)
    private Set<ExcursionCatalog> excursiones = new HashSet<>();

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
