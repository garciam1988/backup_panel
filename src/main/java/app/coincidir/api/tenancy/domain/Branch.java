package app.coincidir.api.tenancy.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Branch — sucursal / local físico de una {@link Brand}.
 *
 * Toda fila operacional del sistema (bot_config, reservas, clientes, tools
 * custom, campañas, etc.) va a tener un `branch_id` apuntando acá. Las
 * tablas que hoy son singleton por marca pasarán a ser por-branch en
 * bloques posteriores.
 *
 * El campo {@link #slug} es lo que aparece en la URL del bot público:
 *   bot.mikhuna.com.ar/palermo  →  branch.slug = "palermo"
 *
 * El bot del cliente final (sin JWT) resuelve la sucursal por path. Si la
 * URL no incluye slug (caso single-branch o cliente nuevo), se usa la
 * sucursal con `default_for_brand=true`. Una marca debe tener exactamente
 * UNA sucursal default — eso se enforcea en BD con un índice único parcial.
 */
@Entity
@Table(name = "branch")
@Getter @Setter
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /**
     * URL-safe único dentro de la marca. Ej: "palermo", "belgrano",
     * "casa-central". Es lo que aparece en la URL del bot público.
     */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    /** Nombre visible en el admin y el selector. Ej: "Sucursal Palermo". */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    /**
     * Timezone de la sucursal. Importante para los jobs proactivos
     * (EmailReminderJob, ProactiveRuleService) — un recordatorio "9 AM" en
     * Mikhuna Mendoza vs Mikhuna BA pueden ser horas distintas en verano.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "America/Argentina/Buenos_Aires";

    /**
     * Marca esta sucursal como default de la marca. Se usa cuando:
     *   - brand.multi_branch_enabled = false → toda la app rutea a esta.
     *   - El bot público recibe una URL sin slug.
     *   - El admin entra sin tener branch activa en localStorage.
     *
     * Una marca debe tener exactamente UNA default. Lo enforcea un índice
     * único en BD (ver V_branch_multi_tenancy_bloque_1.sql).
     */
    @Column(name = "default_for_brand", nullable = false)
    private Boolean defaultForBrand = Boolean.FALSE;

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
        if (defaultForBrand == null) defaultForBrand = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
