package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * ExcelCatalogRow — Una fila de datos de una hoja de Excel.
 *
 * Guardamos todas las columnas como un JSON en {@code dataJson} para no tener
 * que diseñar un esquema por catálogo. La primera fila de cada hoja se asume
 * como headers y se usa como claves del JSON.
 *
 * Ejemplo: si la hoja "Productos" tiene columnas [sku, nombre, precio]:
 *   dataJson = {"sku": "A-123", "nombre": "Remera", "precio": 5000}
 *
 * Para búsquedas simples (WHERE dataJson->>'$.sku' = 'A-123') MySQL 5.7+ tiene
 * soporte JSON nativo que sirve perfecto para catálogos chicos (<10k filas).
 */
@Entity
@Table(name = "excel_catalog_row", indexes = {
    @Index(name = "idx_catalog_sheet", columnList = "catalog_id,sheet_name"),
})
@Getter @Setter
public class ExcelCatalogRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    @Column(name = "sheet_name", nullable = false, length = 100)
    private String sheetName;

    /** Índice de la fila dentro de la hoja (0 = primera fila de datos, después del header). */
    @Column(name = "row_index", nullable = false)
    private Integer rowIndex;

    /** JSON con los valores de la fila: { "columna1": "valor1", "columna2": "valor2", ... } */
    @Column(name = "data_json", nullable = false, columnDefinition = "JSON")
    private String dataJson;
}
