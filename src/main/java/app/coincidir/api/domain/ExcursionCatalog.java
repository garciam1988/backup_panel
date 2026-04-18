package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "excursiones",
        indexes = {
                @Index(name = "idx_excursiones_activo", columnList = "activo")
        }
)
@Getter
@Setter
public class ExcursionCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = true, length = 255)
    private String nombre;

    @Column(name = "descripcion", nullable = true, length = 191)
    private String descripcion;

    @Column(name = "horario_salida")
    private LocalTime horarioSalida;

    @Column(name = "horario_regreso")
    private LocalTime horarioRegreso;

    @Column(name = "costo_usd", precision = 12, scale = 2, nullable = false)
    private BigDecimal costoUsd;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "prestadores_x_excursiones",
            joinColumns = @JoinColumn(name = "excursion_id"),
            inverseJoinColumns = @JoinColumn(name = "prestador_id")
    )
    private Set<Prestador> prestadores = new HashSet<>();

    @Column(name = "created_at", nullable = true, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = true)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
