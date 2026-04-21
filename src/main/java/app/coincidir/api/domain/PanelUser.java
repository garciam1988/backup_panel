package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * PanelUser — usuarios del /panel (operadores y admins de panel).
 *
 * Separados del admin hardcoded de /admin (que vive en env variables).
 * Cada PanelUser tiene un rol que determina qué puede hacer:
 *   - OPERATOR: ve y gestiona pedidos en /panel (no entra a /admin)
 *   - PANEL_ADMIN: ídem + puede gestionar otros PanelUsers (crear/eliminar operadores)
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

    /** Rol: OPERATOR | PANEL_ADMIN */
    @Column(name = "role", length = 30, nullable = false)
    private String role = "OPERATOR";

    /** Paneles habilitados (CSV: "orders,deliveries"). Si null/vacío, todos. */
    @Column(name = "enabled_panels", length = 300)
    private String enabledPanels;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

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
        if (role == null) role = "OPERATOR";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
