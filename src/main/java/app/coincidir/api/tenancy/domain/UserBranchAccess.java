package app.coincidir.api.tenancy.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * UserBranchAccess — mapeo entre un {@code panel_user} y una {@link Branch}.
 *
 * Define a qué sucursal(es) tiene acceso un usuario del admin. Un mismo
 * usuario puede tener varias filas si trabaja en varias sucursales (típico
 * de supervisores o dueños multi-local).
 *
 * Reglas de negocio (enforced en los services, no en BD):
 *
 *   - Usuarios con rol = "DIOS" NO requieren filas acá. DIOS tiene acceso
 *     universal a todas las branches de todas las marcas y elige su
 *     sucursal activa con un selector del header. Si por accidente le
 *     creamos una fila, no rompe nada — simplemente queda ignorada.
 *
 *   - Cualquier otro rol DEBE tener al menos una fila apuntando a la
 *     branch en la que opera. Al loguearse, esa branch se mete en el
 *     JWT y todo el sistema rutea ahí automáticamente.
 *
 *   - Si un usuario no-DIOS tiene 0 filas, no podrá usar el admin
 *     (devuelve 403 al loguear).
 *
 *   - Si un usuario no-DIOS tiene N filas, el frontend muestra un selector
 *     de branch (igual que con DIOS) pero limitado a esas N branches.
 *
 * NO incluimos role_id acá porque los permisos siguen viviendo en
 * PanelUser.role / PanelUser.roleId. El UserBranchAccess solo dice
 * "este user opera en estas branches" — el qué puede hacer adentro
 * sigue siendo del rol global del user.
 */
@Entity
@Table(name = "user_branch_access",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_uba_user_branch",
                        columnNames = {"user_id", "branch_id"})
        },
        indexes = {
                @Index(name = "idx_uba_user", columnList = "user_id"),
                @Index(name = "idx_uba_branch", columnList = "branch_id")
        }
)
@Getter @Setter
public class UserBranchAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK a panel_user.id — no usamos @ManyToOne para mantener el modelo plano. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** FK a branch.id. */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    /**
     * Marca esta branch como la "preferida" del usuario. Cuando el user tiene
     * acceso a varias y arranca su sesión, se rutea automáticamente a la
     * preferida. Si ninguna está marcada, se usa la primera por id.
     *
     * Solo puede haber UNA branch preferida por usuario. Lo enforce el service,
     * no la BD (mantengamos el constraint complejo de "uno true por user_id"
     * a nivel app — vimos en el Bloque 1 lo doloroso que es el GENERATED
     * COLUMN para esto).
     */
    @Column(name = "is_preferred", nullable = false)
    private Boolean isPreferred = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (isPreferred == null) isPreferred = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
