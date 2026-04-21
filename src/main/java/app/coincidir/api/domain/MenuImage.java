package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * MenuImage — galería de imágenes reutilizables del menú digital.
 *
 * Cada imagen tiene un "role" que define dónde se usa en la carta:
 *   - hero         → imagen grande en la tapa del menú
 *   - ambient      → fondo decorativo (con opacity baja)
 *   - logo_center  → logo que va centrado estilo carta elegante
 *   - category:XYZ → foto asociada a la categoría XYZ (matching por nombre)
 *   - generic      → disponible para usar en cualquier lado
 *
 * El binario se guarda en MySQL como MEDIUMBLOB (hasta ~16 MB). Para un bot
 * con <20 imágenes de hasta 500KB cada una (~10 MB total) esto va de sobra.
 */
@Entity
@Table(name = "menu_image")
@Getter @Setter
public class MenuImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 150)
    private String name;

    @Column(name = "role", length = 80)
    private String role = "generic";

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", columnDefinition = "MEDIUMBLOB", nullable = false)
    private byte[] data;

    /** Orden dentro del rol (para elegir cuál es el "principal" si hay varias). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
        if (updatedAt == null) updatedAt = now;
        if (active == null) active = Boolean.TRUE;
        if (sortOrder == null) sortOrder = 0;
        if (role == null) role = "generic";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
