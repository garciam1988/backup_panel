package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * AppRole — rol dinámico definible desde /admin (modo DIOS).
 *
 * Cada rol agrupa un conjunto de permisos serializados en JSON.
 * Estructura del JSON ({@code permissionsJson}):
 *
 * <pre>
 * {
 *   "fullAccess":      false,                       // si es true, ignora todo lo demás (DIOS)
 *   "canManageUsers":  false,                       // CRUD usuarios desde /admin
 *   "canManageRoles":  false,                       // CRUD roles desde /admin
 *   "adminSections":   ["colors","prompt","rules"], // keys del SECTIONS_MENU del AdminPanel
 *   "panelKeys":       ["orders","bot-table:reservas"] // paneles de /panel habilitados
 * }
 * </pre>
 *
 * Si {@code adminSections} es null o lista vacía, el usuario NO puede entrar a /admin
 * (a menos que tenga {@code fullAccess} = true).
 *
 * Roles "system" (DIOS) están protegidos: no se pueden borrar ni renombrar el code.
 */
@Entity
@Table(name = "app_role", indexes = {
        @Index(name = "idx_app_role_code", columnList = "code", unique = true)
})
@Getter @Setter
public class AppRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código corto, único, mayúsculas, snake-case. Ej: "DIOS", "ADMIN", "ENCARGADO". */
    @Column(name = "code", length = 60, nullable = false, unique = true)
    private String code;

    /** Nombre visible. Ej: "Administrador General". */
    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** Descripción opcional para que el DIOS recuerde para qué creó este rol. */
    @Column(name = "description", length = 500)
    private String description;

    /** Permisos serializados como JSON. Ver Javadoc de la clase. */
    @Column(name = "permissions_json", columnDefinition = "LONGTEXT")
    private String permissionsJson;

    /**
     * Si es true, el rol fue creado por el sistema y no puede ser borrado
     * ni se le puede cambiar el {@code code}. Solo el rol DIOS lo usa hoy.
     */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (isSystem == null) isSystem = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
