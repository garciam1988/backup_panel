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
@Table(name = "excel_catalog", uniqueConstraints = {
        // Antes era unique(name) global. Ahora el nombre es único POR sucursal:
        // dos sucursales pueden tener un catálogo llamado "menu" cada una, y
        // además puede existir un "menu" global con branch_id=NULL (NULLs no
        // colisionan en UNIQUE en MySQL, así que sigue funcionando).
        @UniqueConstraint(name = "uq_excel_catalog_name_branch", columnNames = {"name", "branch_id"})
})
@Getter @Setter
public class ExcelCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre lógico / identificador del catálogo. Es con lo que el admin y el
     * bot van a referirse al catálogo. Ej: "productos", "precios_mayorista".
     * Sugerencia: snake_case, sin acentos.
     *
     * Único por sucursal (ver uniqueConstraint a nivel @Table). El mismo nombre
     * puede repetirse entre branches distintas para soportar el caso "cada
     * sucursal tiene su catálogo `menu`".
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    /**
     * Sucursal a la que pertenece este catálogo.
     *
     *   - Valor != null: catálogo "de esa sucursal". Solo lo ve y lo edita
     *     quien tenga acceso a esa branch (o DIOS). El bot solo lo inyecta
     *     al prompt y al menú digital cuando la conversación está scopeada
     *     a esa branch.
     *
     *   - Valor null: catálogo "global a la marca". Visible para TODAS las
     *     sucursales (gerentes y bot, sin importar la branch activa). Es el
     *     escape hatch para fuentes comunes — políticas, FAQ, info de
     *     contacto, etc — que no tiene sentido duplicar 11 veces.
     *
     * Por ahora se guarda solo el id, sin relación JPA explícita, para evitar
     * cargar Branch en cada query y mantener el modelo de tenancy desacoplado.
     */
    @Column(name = "branch_id")
    private Long branchId;

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

    // ── Extensión Fuente de datos ────────────────────────────────────────
    // Hoy los "excel_catalog" son Excel/CSV, pero la tabla se usa como el
    // catálogo general de "fuentes" — PDF, Word, TXT, imágenes (OCR),
    // subidos directo o desde URL remota con auto-refresh.

    /** Tipo de fuente: "file" (upload manual) | "url" (descarga remota). */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = "file";

    /** MIME type detectado. Ej: "application/pdf", "image/png", etc. */
    @Column(name = "mime_type", length = 120)
    private String mimeType;

    /** URL original si se cargó desde remoto. */
    @Column(name = "original_url", length = 2000)
    private String originalUrl;

    /** Último refresh exitoso (solo si sourceType = "url"). */
    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    /** Cada cuántas horas auto-actualizar desde la URL. null = nunca. */
    @Column(name = "auto_refresh_hours")
    private Integer autoRefreshHours;

    /**
     * Texto extraído del archivo (PDF/Word/TXT/OCR). Para Excel/CSV puede
     * quedar null (se usa la representación tabular como hoy). Para el resto
     * es el contenido que se inyecta al prompt del bot (Modo A).
     */
    @Lob
    @Column(name = "extracted_text", columnDefinition = "MEDIUMTEXT")
    private String extractedText;

    /** Cantidad aproximada de tokens del extractedText. Se calcula al subir/refrescar. */
    @Column(name = "token_count")
    private Integer tokenCount;

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
