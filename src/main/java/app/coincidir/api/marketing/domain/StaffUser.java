package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * StaffUser — Mozo / cajero / encargado del local que opera la Staff App.
 *
 * Login: PIN de 4-8 dígitos asociado a un nombre.
 * Auth: el backend genera un JWT staff con scope limitado (solo endpoints
 * /api/staff-app/**), distinto del JWT admin.
 *
 * Cada operación queda registrada con staff_user_id en loyalty_transaction
 * para auditoría (quién cargó qué a quién).
 *
 * Singleton-friendly: por ahora no hay multi-tenancy por staff (todos los
 * mozos pertenecen al mismo local = bot_config id=1). Cuando haya
 * multi-tenancy real, se agrega tenant_id.
 */
@Entity
@Table(name = "staff_user", indexes = {
    @Index(name = "ix_staff_user_pin_hash", columnList = "pin_hash"),
    @Index(name = "ix_staff_user_active", columnList = "active"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre del mozo (ej. "Juan", "María Cajera Turno Noche"). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Hash bcrypt del PIN. NUNCA guardamos el PIN en plano.
     * El PIN se setea/cambia desde /marketing y queda hasheado.
     */
    @Column(name = "pin_hash", nullable = false, length = 200)
    private String pinHash;

    /**
     * Rol del staff. Por ahora dos:
     *   - MOZO: puede sumar stamps, validar redemptions, aplicar cupones.
     *   - ENCARGADO: lo mismo + acceso a historial completo de operaciones.
     * Se modela como string para extensibilidad (futuro: CHEF, KIOSCO, etc.)
     */
    @Column(name = "role", nullable = false, length = 30)
    @Builder.Default
    private String role = "MOZO";

    /**
     * Si está en false, no puede loguearse. Se "borra" softmente para no
     * romper el historial de transacciones que referencian al staff.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (active == null) active = true;
        if (role == null) role = "MOZO";
    }
}
