package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Tabla catálogo ("Servicios").
 */
@Entity
@Table(
        name = "servicios",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_servicios_code", columnNames = "code")
        }
)
@Getter
@Setter
public class ServiceDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 50)
    private ServiceCode code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
