package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity @Table(name = "user_account")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password; // BCrypt

    @Column(nullable = false)
    private String role; // e.g. "SELLER" (sin "ROLE_" acá)

    // Datos de perfil (para UI)
    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * Flag para acceso al módulo /manager (ARViz / Jarvis).
     *
     * Independiente del role: cualquier user (SELLER, ADMIN, etc.) puede tener
     * o no acceso al manager. Por defecto false. Se setea desde AdminPanel
     * → Manager Section → "Usuarios autorizados".
     */
    @Column(name = "manager_access", nullable = false)
    @lombok.Builder.Default
    private Boolean managerAccess = Boolean.FALSE;

    /**
     * Perfil extendido en formato JSON (flexible para el User Panel).
     * Se guarda como texto (LONGTEXT) para evitar cambios de esquema frecuentes.
     */
    @Column(name = "profile_json", columnDefinition = "LONGTEXT")
    private String profileJson;
}
