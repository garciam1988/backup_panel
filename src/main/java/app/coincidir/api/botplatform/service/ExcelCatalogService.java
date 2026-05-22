package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import app.coincidir.api.botplatform.repository.ExcelCatalogRowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * ExcelCatalogService — Parseo y carga de archivos Excel a las tablas
 * excel_catalog y excel_catalog_row.
 *
 * Reglas de parseo:
 *   • Primera fila NO vacía de cada hoja = headers (nombres de columna).
 *   • Columnas sin header se ignoran (evita columnas "sucias" al final).
 *   • Filas 100% vacías se saltan (no se cargan).
 *   • Números se guardan como number. Fechas como ISO-8601 string.
 *     Todo lo demás como string.
 *   • Las filas se guardan como JSON: { "header1": valor1, "header2": valor2 }
 *
 * Límites:
 *   • Max 100.000 filas totales por catálogo (si supera se rechaza).
 *   • Hojas con menos de 2 filas (solo header) se saltan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelCatalogService {

    private static final int MAX_ROWS_PER_CATALOG = 100_000;

    private final ExcelCatalogRepository catalogRepo;
    private final ExcelCatalogRowRepository rowRepo;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * EntityManager para forzar flush explícito entre DELETE y INSERT.
     * Sin esto, JPA puede reordenar las operaciones y ejecutar el DELETE
     * después de los INSERT, borrando las filas recién insertadas
     * (causa: las filas viejas no se borran y las nuevas se pierden,
     * dejando el catálogo con datos inconsistentes o vacío).
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * Carga (o reemplaza) un catálogo a partir de un archivo Excel.
     *
     * @param name        nombre lógico del catálogo (ej: "productos")
     * @param description descripción opcional
     * @param branchId    sucursal a la que pertenece (null = global a la marca).
     *                    El upsert busca por (name, branchId) — dos sucursales
     *                    pueden tener un "menu" cada una y conviven sin chocar.
     * @param file        archivo .xlsx (o .xls)
     * @param uploadedBy  usuario que sube
     * @return metadata del catálogo creado/actualizado
     */
    @Transactional
    public ExcelCatalog uploadCatalog(String name, String description, Long branchId,
                                       MultipartFile file, String uploadedBy) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("El archivo está vacío");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre del catálogo es obligatorio");

        String cleanName = name.trim();

        // ─────────────────────────────────────────────────────────────────
        // FIX (catálogos): garantizar que el upload arranque SIEMPRE limpio.
        //
        // Problema histórico:
        //   - JPA mezcla un DELETE (@Modifying) con saveAll() de filas nuevas
        //     en la misma transacción; sin flush explícito el orden de
        //     ejecución no está garantizado y a veces los INSERT se hacen
        //     primero, luego corre el DELETE y borra las filas recién
        //     insertadas (queda el catálogo con 0 filas o con basura).
        //   - Además, si un upload previo del mismo nombre falló a medio
        //     camino, podían quedar filas huérfanas referenciando un
        //     catalog_id "fantasma".
        //
        // Solución:
        //   1. Si ya existe un catálogo con ese name → borrar sus filas y
        //      flushear ANTES de cualquier insert.
        //   2. Si NO existe → guardar primero el nuevo catálogo (para tener
        //      id real) y flushear, así los inserts van con un id válido y
        //      no se mezclan con un DELETE pendiente.
        //   3. Recién después parsear y guardar las filas nuevas.
        // ─────────────────────────────────────────────────────────────────

        Optional<ExcelCatalog> existing = catalogRepo.findByNameAndBranchId(cleanName, branchId);

        // Validación de colisión con global:
        // Si se está creando un catálogo NO global (branchId != null) y ya existe
        // un GLOBAL con el mismo nombre, falla. Razón: el bot, scopeado a esa
        // branch, vería los dos catálogos (su branch + globales) con el mismo
        // nombre, y eso rompe la inyección al prompt y la generación de tools
        // (ambigüedad). Solo aplica cuando es una creación nueva — en upsert
        // (existing.isPresent) ya sabemos que estamos updateando un registro
        // específico y no hay riesgo.
        if (existing.isEmpty() && branchId != null) {
            Optional<ExcelCatalog> conflictGlobal = catalogRepo.findByNameAndBranchId(cleanName, null);
            if (conflictGlobal.isPresent()) {
                throw new IllegalArgumentException(
                        "Ya existe un catálogo GLOBAL con el nombre '" + cleanName + "'. " +
                        "Elegí otro nombre o pedile a un administrador que renombre/borre el global.");
            }
        }

        ExcelCatalog catalog = existing.orElseGet(ExcelCatalog::new);
        catalog.setName(cleanName);
        catalog.setBranchId(branchId);
        if (description != null) catalog.setDescription(description);
        catalog.setOriginalFilename(file.getOriginalFilename());
        catalog.setSizeBytes(file.getSize());
        catalog.setUploadedBy(uploadedBy);
        catalog.setActive(true);

        if (existing.isPresent() && catalog.getId() != null) {
            // Upsert: borrar filas viejas y flushear inmediatamente para
            // que el DELETE se ejecute ANTES de los INSERT siguientes.
            long oldCount = rowRepo.countByCatalogId(catalog.getId());
            rowRepo.deleteByCatalogId(catalog.getId());
            em.flush();
            log.info("excel_catalog upsert: name={} branch={} filas viejas borradas={}",
                    cleanName, branchId, oldCount);
        }

        // Guardar metadata del catálogo (asigna id si era nuevo) y flushear
        // para garantizar que el id esté disponible y persistido antes de
        // insertar las filas hijas.
        catalog = catalogRepo.save(catalog);
        em.flush();

        // Parsear
        List<String> sheetNames = new ArrayList<>();
        List<ExcelCatalogRow> allRows = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = openWorkbook(is, file.getOriginalFilename())) {

            DataFormatter fmt = new DataFormatter();

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                // Saltear hojas vacías o solo con header
                int lastRow = sheet.getLastRowNum();
                if (lastRow < 1) continue;

                // Detectar fila de headers (primera no vacía)
                Row headerRow = null;
                int headerRowIdx = -1;
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null && hasAnyCellWithValue(row, fmt)) {
                        headerRow = row;
                        headerRowIdx = r;
                        break;
                    }
                }
                if (headerRow == null) continue;

                // Extraer headers
                List<String> headers = new ArrayList<>();
                int lastCol = headerRow.getLastCellNum();
                for (int c = 0; c < lastCol; c++) {
                    Cell cell = headerRow.getCell(c);
                    String header = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    headers.add(header);
                }
                // Si no hay al menos un header válido, saltear la hoja
                if (headers.stream().allMatch(String::isBlank)) continue;

                sheetNames.add(sheetName);

                // Parsear filas de datos
                int rowIndex = 0;
                for (int r = headerRowIdx + 1; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || !hasAnyCellWithValue(row, fmt)) continue;

                    // Importante: iteramos en orden de headers y SIEMPRE incluimos
                    // cada columna (aunque la celda esté vacía guardamos "").
                    // Antes se hacía "if (value != null) put(...)" lo que provocaba
                    // dos problemas:
                    //   1. Cada fila tenía un set distinto de keys según qué celdas
                    //      estaban llenas → el frontend perdía columnas en el
                    //      preview cuando la 1ra fila no tenía ese campo.
                    //   2. El orden de columnas terminaba dependiendo de la 1ra
                    //      fila con datos completos, no del orden real del Excel.
                    // Con este fix, todas las filas tienen las mismas keys, en el
                    // mismo orden que los headers del Excel.
                    Map<String, Object> rowData = new LinkedHashMap<>();
                    boolean hasAnyRealValue = false;
                    for (int c = 0; c < headers.size(); c++) {
                        String header = headers.get(c);
                        if (header.isBlank()) continue; // ignorar columnas sin header

                        Cell cell = row.getCell(c);
                        Object value = extractCellValue(cell, fmt);
                        if (value != null) {
                            rowData.put(header, value);
                            hasAnyRealValue = true;
                        } else {
                            rowData.put(header, "");
                        }
                    }

                    if (!hasAnyRealValue) continue;

                    ExcelCatalogRow erow = new ExcelCatalogRow();
                    erow.setCatalogId(catalog.getId());
                    erow.setSheetName(sheetName);
                    erow.setRowIndex(rowIndex++);
                    erow.setDataJson(jsonMapper.writeValueAsString(rowData));
                    allRows.add(erow);

                    if (allRows.size() > MAX_ROWS_PER_CATALOG) {
                        throw new IllegalStateException("El archivo supera el máximo de " + MAX_ROWS_PER_CATALOG + " filas");
                    }
                }
            }
        }

        // Guardar filas en batch y flushear para que queden persistidas
        // antes de actualizar la metadata final (orden estable).
        if (!allRows.isEmpty()) {
            rowRepo.saveAll(allRows);
            em.flush();
        }

        // Actualizar metadata
        catalog.setSheetsJson(jsonMapper.writeValueAsString(sheetNames));
        catalog.setTotalRows(allRows.size());
        catalog.setUpdatedAt(Instant.now());
        catalog = catalogRepo.save(catalog);

        log.info("excel_catalog cargado: name={}, hojas={}, filas={}, por={}",
                catalog.getName(), sheetNames.size(), allRows.size(), uploadedBy);

        return catalog;
    }

    /** Primeras N filas de un catálogo (para preview en el admin). */
    @Transactional(readOnly = true)
    public List<ExcelCatalogRow> preview(Long catalogId, String sheetName, int limit) {
        List<ExcelCatalogRow> all = (sheetName != null && !sheetName.isBlank())
                ? rowRepo.findByCatalogIdAndSheetNameOrderByRowIndexAsc(catalogId, sheetName.trim())
                : rowRepo.findByCatalogIdOrderBySheetNameAscRowIndexAsc(catalogId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    @Transactional
    public void deleteCatalog(Long catalogId) {
        // Borrar filas hijas primero, flushear para que el DELETE se ejecute
        // ANTES del delete del catálogo padre. Sin flush, JPA puede reordenar
        // y dejar filas huérfanas en excel_catalog_row.
        rowRepo.deleteByCatalogId(catalogId);
        em.flush();
        catalogRepo.deleteById(catalogId);
    }

    // ── Helpers de parseo ─────────────────────────────────────────────

    private Workbook openWorkbook(InputStream is, String filename) throws IOException {
        // POI detecta el formato automáticamente con WorkbookFactory, pero queremos dar
        // un mensaje claro si el archivo no es xlsx/xls. Usamos XSSF para .xlsx (el común).
        if (filename != null && filename.toLowerCase().endsWith(".xls")) {
            return WorkbookFactory.create(is);
        }
        return new XSSFWorkbook(is);
    }

    private boolean hasAnyCellWithValue(Row row, DataFormatter fmt) {
        int last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = fmt.formatCellValue(cell);
                if (v != null && !v.isBlank()) return true;
            }
        }
        return false;
    }

    /**
     * Devuelve el valor de una celda con el tipo correcto:
     *   - Numérico entero → Long
     *   - Numérico decimal → Double
     *   - Fecha → ISO-8601 string
     *   - Booleano → Boolean
     *   - Fórmula → se evalúa y se trata según su resultado
     *   - Resto → String (usando DataFormatter para respetar el formato visible)
     */
    private Object extractCellValue(Cell cell, DataFormatter fmt) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }

        switch (type) {
            case BLANK:
                return null;
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString(); // ISO-8601
                }
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Long.MAX_VALUE) {
                    return (long) d;
                }
                return d;
            case STRING:
                String s = cell.getStringCellValue();
                return s != null ? s.trim() : null;
            default:
                String formatted = fmt.formatCellValue(cell);
                return formatted != null && !formatted.isBlank() ? formatted.trim() : null;
        }
    }

    @SuppressWarnings("unused")
    private static String colLetter(int c) {
        return CellReference.convertNumToColString(c);
    }
}
