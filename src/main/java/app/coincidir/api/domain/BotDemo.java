package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotDemo — snapshot completo de la configuración del bot pensado como
 * "plantilla" para demos.
 *
 * Incluye:
 *   - config completo del BotConfig (serializado como JSON para simplificar)
 *   - catálogos Excel (metadata + filas, también como JSON)
 *   - conectores
 *   - tools
 *
 * Al "aplicar" una demo, el backend reemplaza la config vigente y las tablas
 * relacionadas con el contenido del snapshot.
 */
@Entity
@Table(name = "bot_demo", indexes = {
        @Index(name = "idx_bot_demo_name", columnList = "name"),
        @Index(name = "idx_bot_demo_updated_at", columnList = "updated_at")
})
@Getter @Setter
public class BotDemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre descriptivo de la demo (ej: "El Parmegiano", "Ferretería"). */
    @Column(name = "name", length = 150, nullable = false, unique = true)
    private String name;

    /** Descripción opcional para diferenciar entre demos similares. */
    @Column(name = "description", length = 500)
    private String description;

    /** Snapshot completo del BotConfig como JSON. */
    @Column(name = "config_json", columnDefinition = "LONGTEXT", nullable = false)
    private String configJson;

    /** Catálogos Excel serializados (incluye filas). */
    @Column(name = "catalogs_json", columnDefinition = "LONGTEXT")
    private String catalogsJson;

    /** Conectores de BDs serializados. */
    @Column(name = "connectors_json", columnDefinition = "LONGTEXT")
    private String connectorsJson;

    /** Tools del bot serializadas. */
    @Column(name = "tools_json", columnDefinition = "LONGTEXT")
    private String toolsJson;

    /** Última vez que se aplicó esta demo (para ordenar y mostrar en la UI). */
    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
