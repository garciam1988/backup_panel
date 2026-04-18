package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * ExcelCatalog — Metadata de un archivo Excel subido por el admin.
 *
 * Los archivos son catálogos (productos, precios, listas de cosas que cambian
 * poco). El admin los sube desde /admin, se parsean con Apache POI y las filas
 * se guardan en {@link ExcelCatalogRow}.
 *
 * Flujo:
 *   1. Admin sube file.xlsx → se crea fila en esta tabla + N filas en _row
 *   2. Si re-sube (mismo nombre), se eliminan las filas viejas y se reemplazan
 *   3. El bot (Fase Excel-3) consultará via tools del ecosistema BotTool
 */
@Entity
@Table(name = "excel_catalog")
@Getter @Setter
public class ExcelCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre lógico / identificador del catálogo. Es con lo que el admin y el
     * bot van a referirse al catálogo. Ej: "productos", "precios_mayorista".
     * Sugerencia: snake_case, sin acentos.
     */
    @Column(name = "name", nullable = false, length = 100, unique = true)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    /** Nombre del archivo original subido (ej: "Catalogo Productos 2026.xlsx"). */
    @Column(name = "original_filename", length = 300)
    private String originalFilename;

    /** Tamaño en bytes del archivo subido. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * JSON array con los nombres de las hojas del Excel.
     * Ej: ["Productos", "Precios", "Hoja1"]
     */
    @Column(name = "sheets_json", length = 2000)
    private String sheetsJson;

    /** Cantidad total de filas de datos cargadas (suma de todas las hojas). */
    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Usuario que subió el archivo. */
    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
