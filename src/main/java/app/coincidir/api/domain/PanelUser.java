package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * PanelUser — usuario del sistema (operadores /panel y administradores /admin).
 *
 * Originalmente nació para el /panel, pero a partir del soporte de roles
 * dinámicos también es la fuente de verdad para usuarios del /admin.
 *
 * Modelo de roles:
 *  - El campo legacy {@code role} (String) se sigue manteniendo por compatibilidad
 *    con código viejo. Sus valores son típicamente: OPERATOR | PANEL_ADMIN | DIOS | ADMIN | ENCARGADO.
 *  - El nuevo {@code roleId} apunta a {@link AppRole}, que define los permisos efectivos
 *    en JSON. SI {@code roleId} está seteado, el legacy {@code role} se mantiene
 *    sincronizado con el {@code code} de ese AppRole para que nada viejo se rompa.
 *
 * La password se guarda hasheada con BCrypt.
 */
@Entity
@Table(name = "panel_user", indexes = {
        @Index(name = "idx_panel_user_username", columnList = "username")
})
@Getter @Setter
public class PanelUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", length = 80, nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", length = 150)
    private String displayName;

    /** Password hasheada con BCrypt. */
    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    /**
     * Rol legacy (string). Se mantiene en sync con el {@code code} de {@link #roleId}.
     * Valores típicos: OPERATOR, PANEL_ADMIN, DIOS, ADMIN, ENCARGADO.
     * Mantener este campo nos evita romper código existente que filtra por string.
     */
    @Column(name = "role", length = 60, nullable = false)
    private String role = "OPERATOR";

    /**
     * FK al rol dinámico definido en {@link AppRole}. Es la fuente de verdad
     * de permisos para los flujos nuevos. Puede ser null para usuarios viejos
     * (en cuyo caso se aplica el campo legacy {@code role} con permisos hardcodeados).
     */
    @Column(name = "role_id")
    private Long roleId;

    /**
     * Paneles de /panel habilitados (CSV: "orders,bot-table:reservas").
     * Si null/vacío Y el rol no define {@code panelKeys}, se aplica el default del rol.
     * Si null/vacío Y el rol no tiene panelKeys, NO ve paneles (a menos que tenga fullAccess).
     *
     * Nota: este campo permite override por usuario (un operador puede tener
     * más o menos paneles que el default del rol). Para mantenerlo simple,
     * SI este campo está seteado, gana sobre el rol.
     */
    @Column(name = "enabled_panels", length = 500)
    private String enabledPanels;

    /**
     * Secciones del /admin habilitadas (CSV de keys del SECTIONS_MENU).
     * Funciona igual que {@link #enabledPanels}: si está seteado, gana sobre el rol.
     * Si null/vacío, se aplica lo que diga el rol en {@code adminSections}.
     */
    @Column(name = "enabled_admin_sections", length = 1000)
    private String enabledAdminSections;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    /**
     * Si es true, el usuario fue creado por el sistema y no puede ser borrado
     * ni desactivado por la UI. Hoy solo lo usa el DIOS sembrado por env vars.
     */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = Boolean.FALSE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (active == null) active = Boolean.TRUE;
        if (isSystem == null) isSystem = Boolean.FALSE;
        if (role == null) role = "OPERATOR";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
