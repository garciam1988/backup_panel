package app.coincidir.api.coinbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ClientesAdminController — endpoints para el módulo /admin > Clientes.
 *
 * Bajo /api/admin/** (requiere JWT).
 *   GET  /api/admin/clientes?q=&page=&size=  → lista paginada filtrada por texto libre
 *   GET  /api/admin/clientes/stats           → métricas agregadas
 *   GET  /api/admin/clientes/{key}           → detalle de un cliente (su clienteKey URL-encoded)
 *   GET  /api/admin/clientes.xlsx?q=         → exporta a Excel con los filtros aplicados
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/clientes")
@RequiredArgsConstructor
public class ClientesAdminController {

    private final ClientesService service;

    /**
     * Lista clientes con búsqueda libre y paginación.
     * El filtro `q` matchea contra nombre, apellido, mail y teléfono (case-insensitive).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        List<ClientesService.ClienteDto> all = service.listAll();
        List<ClientesService.ClienteDto> filtered = filter(all, q);

        int from = Math.max(0, page) * Math.max(1, size);
        int to = Math.min(from + size, filtered.size());
        List<ClientesService.ClienteDto> pageItems =
                from < filtered.size() ? filtered.subList(from, to) : Collections.emptyList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", pageItems);
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", filtered.size());
        out.put("totalPages", (int) Math.ceil(filtered.size() / (double) Math.max(1, size)));
        return out;
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ClientesService.StatsDto stats() {
        return service.stats();
    }

    @GetMapping("/{clienteKey}")
    @Transactional(readOnly = true)
    public ResponseEntity<ClientesService.ClienteDetalleDto> detail(@PathVariable String clienteKey) {
        ClientesService.ClienteDetalleDto d = service.detail(clienteKey);
        if (d == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(d);
    }

    /**
     * Excel export de la lista (con el filtro q opcional).
     * Columnas: Nombre, Apellido, Email, Teléfono, # Reservas, Dispositivo,
     * Primer contacto, Último contacto.
     */
    @GetMapping(value = "/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportExcel(@RequestParam(required = false) String q) throws Exception {
        List<ClientesService.ClienteDto> all = service.listAll();
        List<ClientesService.ClienteDto> filtered = filter(all, q);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Clientes");

            // Estilos
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_RED.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // Header
            String[] headers = {
                "Nombre", "Apellido", "Email", "Teléfono",
                "# Reservas", "Dispositivo", "Detalle", "Sistema", "Navegador",
                "País", "Provincia", "Ciudad",
                "Primer contacto", "Último contacto"
            };
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            DateTimeFormatter fmt = DateTimeFormatter
                    .ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.of("America/Argentina/Buenos_Aires"));

            int rowIdx = 1;
            for (ClientesService.ClienteDto c : filtered) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safe(c.nombre));
                row.createCell(1).setCellValue(safe(c.apellido));
                row.createCell(2).setCellValue(safe(c.email));
                row.createCell(3).setCellValue(safe(c.telefono));
                row.createCell(4).setCellValue(c.reservasCount);
                row.createCell(5).setCellValue(safe(c.dispositivoPrincipal));
                row.createCell(6).setCellValue(safe(c.dispositivoDetalle));
                row.createCell(7).setCellValue(safe(c.sistemaOperativo));
                row.createCell(8).setCellValue(safe(c.navegador));
                row.createCell(9).setCellValue(safe(c.geoPais));
                row.createCell(10).setCellValue(safe(c.geoProvincia));
                row.createCell(11).setCellValue(safe(c.geoCiudad));
                row.createCell(12).setCellValue(formatInstant(c.firstSeenAt, fmt));
                row.createCell(13).setCellValue(formatInstant(c.lastSeenAt, fmt));
            }

            // Resumen al final
            int summaryRow = rowIdx + 1;
            Row sum = sheet.createRow(summaryRow);
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(boldFont);
            Cell labelCell = sum.createCell(0);
            labelCell.setCellValue("TOTAL");
            labelCell.setCellStyle(boldStyle);
            Cell valueCell = sum.createCell(4);
            valueCell.setCellValue(filtered.stream().mapToInt(c -> c.reservasCount).sum());
            valueCell.setCellStyle(boldStyle);

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);

            String filename = "clientes_" + Instant.now().toString().replace(":", "-").substring(0, 19) + ".xlsx";
            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            respHeaders.setContentDispositionFormData("attachment", filename);

            return new ResponseEntity<>(bos.toByteArray(), respHeaders, 200);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private List<ClientesService.ClienteDto> filter(List<ClientesService.ClienteDto> all, String q) {
        if (q == null || q.isBlank()) return all;
        String needle = q.trim().toLowerCase();
        List<ClientesService.ClienteDto> out = new ArrayList<>();
        for (ClientesService.ClienteDto c : all) {
            String hay = (
                    nullToEmpty(c.nombre) + " " +
                    nullToEmpty(c.apellido) + " " +
                    nullToEmpty(c.email) + " " +
                    nullToEmpty(c.telefono) + " " +
                    nullToEmpty(c.dispositivoPrincipal) + " " +
                    nullToEmpty(c.sistemaOperativo)
            ).toLowerCase();
            if (hay.contains(needle)) out.add(c);
        }
        return out;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String formatInstant(Instant i, DateTimeFormatter fmt) {
        return i == null ? "" : fmt.format(i);
    }
}
