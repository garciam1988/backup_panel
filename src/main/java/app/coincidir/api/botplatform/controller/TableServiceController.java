package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.TableServiceSession;
import app.coincidir.api.botplatform.repository.TableServiceSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TableServiceController — endpoints de lectura para las sesiones "en
 * servicio" de las mesas.
 *
 * El frontend de Smart Tables usa /active para pintar el cronómetro de
 * cada mesa que está en servicio (recordando que el frontend dejó de
 * usar localStorage para esto — ahora consume estos endpoints).
 *
 * El histórico se usa para reportes futuros: duración promedio por
 * turno, mesas más rotadas, picos de demanda, etc.
 *
 * Solo lectura — la creación/cierre de sesiones la hace
 * TableServiceTrackerService como side-effect de cambios en records.
 * NO exponemos endpoints de POST/PUT/DELETE intencionalmente: la fuente
 * de verdad es el status de la reserva, no una acción independiente.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/table-service")
@RequiredArgsConstructor
public class TableServiceController {

    private final TableServiceSessionRepository sessionRepo;

    /**
     * Sesiones activas (ended_at IS NULL) de una sucursal. Lo poltea el
     * frontend cada 5s para mantener el cronómetro al día.
     *
     * Si {@code branchId} viene null, devolvemos lista vacía — la query
     * cruza tenancy y queremos que el caller siempre especifique branch.
     */
    @GetMapping("/active")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> active(@RequestParam(required = false) Long branchId) {
        if (branchId == null) return List.of();
        List<TableServiceSession> sessions = sessionRepo.findActiveByBranch(branchId);
        List<Map<String, Object>> out = new ArrayList<>(sessions.size());
        for (TableServiceSession s : sessions) out.add(toDto(s));
        return out;
    }

    /**
     * Historial filtrado. Todos los parámetros son opcionales salvo
     * branchId. {@code from} y {@code to} aceptan ISO date (YYYY-MM-DD)
     * o ISO datetime. Default page size = 50, max 200.
     *
     * El ordenamiento es por startedAt descendente (más recientes primero),
     * incluyendo sólo sesiones CERRADAS — las activas se consultan vía
     * /active.
     *
     * Response: { items: [...], page, size, hasMore }
     */
    @GetMapping("/history")
    @Transactional(readOnly = true)
    public Map<String, Object> history(@RequestParam Long branchId,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String tableLabel,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        if (branchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branchId requerido");
        }
        if (size < 1) size = 1;
        if (size > 200) size = 200;
        if (page < 0) page = 0;

        Instant fromInst = parseDateOrDateTime(from, false);
        Instant toInst   = parseDateOrDateTime(to, true);

        // Pedimos size+1 para detectar hasMore sin un count() extra.
        Pageable pageable = PageRequest.of(page, size + 1);
        String tableLabelFilter = (tableLabel != null && !tableLabel.isBlank()) ? tableLabel : null;

        List<TableServiceSession> sessions = sessionRepo.findHistory(
                branchId, tableLabelFilter, fromInst, toInst, pageable);

        boolean hasMore = sessions.size() > size;
        if (hasMore) sessions = sessions.subList(0, size);

        List<Map<String, Object>> items = new ArrayList<>(sessions.size());
        for (TableServiceSession s : sessions) items.add(toDto(s));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("page", page);
        resp.put("size", size);
        resp.put("hasMore", hasMore);
        return resp;
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toDto(TableServiceSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("branchId", s.getBranchId());
        m.put("recordId", s.getRecordId());
        m.put("botTableId", s.getBotTableId());
        m.put("tableLabel", s.getTableLabel());
        m.put("personsCount", s.getPersonsCount());
        m.put("title", s.getTitle());
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        m.put("endedAt", s.getEndedAt() != null ? s.getEndedAt().toString() : null);
        m.put("endedReason", s.getEndedReason());
        // Tiempo transcurrido (para sesiones activas) o duración total
        // (para cerradas). En segundos para mantener la API ligera —
        // el frontend formatea HH:MM:SS.
        long startMs = s.getStartedAt() != null ? s.getStartedAt().toEpochMilli() : 0;
        long endMs = s.getEndedAt() != null ? s.getEndedAt().toEpochMilli() : System.currentTimeMillis();
        m.put("durationSec", Math.max(0, (endMs - startMs) / 1000));
        m.put("active", s.isActive());
        return m;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parsea un string como Instant. Acepta:
     *   - ISO date: "2026-05-20" — se interpreta como 00:00 UTC (o 23:59:59
     *     UTC si {@code endOfDay} = true, para que un filtro `to=2026-05-20`
     *     incluya todo ese día).
     *   - ISO datetime: "2026-05-20T15:30:00Z" — se usa tal cual.
     * Si el string es null/blank, devuelve null (= sin filtro).
     */
    private static Instant parseDateOrDateTime(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        try {
            // Probar como Instant directo (ISO con offset/Z).
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignore) { /* fallthrough */ }
        try {
            // Probar como LocalDate (yyyy-MM-dd).
            LocalDate d = LocalDate.parse(trimmed);
            if (endOfDay) {
                return d.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
            }
            return d.atStartOfDay(ZoneId.of("UTC")).toInstant();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato de fecha inválido: " + s + " (esperado YYYY-MM-DD o ISO datetime)");
        }
    }
}
