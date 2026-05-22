package app.coincidir.api.tenancy.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Brand — marca / cliente / cuenta del sistema.
 *
 * Una marca corresponde a un cliente comercial (Mikhuna Nikkei, YES Travel,
 * Brasas Argentinas, etc.). Hoy hay UNA marca por deploy de Railway, pero el
 * modelo permite varias en el mismo deploy si en el futuro se quisiera.
 *
 * Cada marca tiene N {@link Branch} (sucursales). Aunque la marca tenga una
 * sola sucursal (caso YES Travel, Brasas), el modelo es uniforme: siempre
 * hay al menos una branch "default" (`default_for_brand=true`).
 *
 * El flag {@link #multiBranchEnabled} controla si la UI del admin muestra el
 * selector de sucursal. Si está en false, el sistema se comporta exactamente
 * como antes del Bloque 1: todo va a la única sucursal default sin que el
 * usuario lo note.
 */
@Entity
@Table(name = "brand")
@Getter @Setter
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identificador URL-safe único. Ej: "mikhuna-nikkei", "yes-travel".
     * Se deriva del name por defecto, pero se puede editar.
     */
    @Column(name = "slug", nullable = false, length = 64, unique = true)
    private String slug;

    /** Nombre comercial visible. Ej: "Mikhuna Nikkei". */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /**
     * Si está en true, el admin muestra el selector de sucursal y la app
     * filtra por branch en cada request. Si está en false (default), todo
     * se rutea a la sucursal default sin que el usuario lo note.
     *
     * IMPORTANTE: cambiar este flag NO migra data — solo cambia el
     * comportamiento de la UI. Para activar multi-branch hay que:
     *   1. Crear las sucursales adicionales con un INSERT en branch.
     *   2. (En bloques futuros) Migrar la data existente al branch_id que
     *      le corresponda — la default ya tiene toda la data legacy.
     *   3. Setear este flag en true.
     */
    @Column(name = "multi_branch_enabled", nullable = false)
    private Boolean multiBranchEnabled = Boolean.FALSE;

    /**
     * Timezone default heredada por las sucursales nuevas. Cada Branch puede
     * tener su propia timezone si difiere (ej: marca con sucursales en
     * Mendoza y BA, distinto horario en verano).
     */
    @Column(name = "timezone_default", nullable = false, length = 64)
    private String timezoneDefault = "America/Argentina/Buenos_Aires";

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (active == null) active = Boolean.TRUE;
        if (multiBranchEnabled == null) multiBranchEnabled = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
