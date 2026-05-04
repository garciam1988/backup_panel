package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PanelBotTableService — lógica del panel "tipo reservas" para tablas custom:
 * detección de conflictos por mesa + auto-asignación de mesa.
 *
 * Trabaja sobre el panelConfigJson de la tabla. Si la tabla no tiene mesas
 * configuradas, los chequeos de conflicto usan solapamiento por horario solo.
 *
 * El JSON de panelConfig esperado:
 *   {
 *     "calendarConfig": {
 *       "dateColumn": "fecha",
 *       "tableColumn": "mesa",         (opcional)
 *       "personsColumn": "personas",   (opcional)
 *       "durationMinutes": 90,
 *       "tables": [
 *         { "id": "M1", "label": "Mesa 1", "capacity": 4 },
 *         ...
 *       ],
 *       "autoAssignTable": true
 *     }
 *   }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PanelBotTableService {

    private final BotTableRecordRepository recordRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Resultado del chequeo de conflicto: lista de records que se solapan. */
    public static class ConflictReport {
        public boolean hasConflict;
        public List<ConflictItem> conflicts = new ArrayList<>();
        /** Mesa sugerida si hay autoAssign y la elegida no servía. */
        public String suggestedTableId;
        public String suggestedTableLabel;
        /** Mensaje human-readable. */
        public String message;
    }

    public static class ConflictItem {
        public Long recordId;
        public String tableId;       // id de la mesa
        public String tableLabel;
        public String startsAt;      // ISO local
        public String endsAt;
        public String summary;       // título corto, ej "Juan · 4 pers"
    }

    public static class AutoAssignResult {
        public String tableId;
        public String tableLabel;
        public boolean assigned;
        public String reason; // por qué no se asignó
    }

    /** Parsea el panelConfig.calendarConfig de una BotTable. Devuelve null si no hay panel/calendar. */
    public CalendarCfg readCalendarCfg(BotTable t) {
        if (t == null || t.getPanelConfigJson() == null || t.getPanelConfigJson().isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(t.getPanelConfigJson());
            if (root == null || !root.has("calendarConfig")) return null;
            JsonNode cc = root.get("calendarConfig");
            CalendarCfg out = new CalendarCfg();
            out.dateColumn      = textOrNull(cc, "dateColumn");
            out.titleColumn     = textOrNull(cc, "titleColumn");
            out.statusColumn    = textOrNull(cc, "statusColumn");
            out.personsColumn   = textOrNull(cc, "personsColumn");
            out.tableColumn     = textOrNull(cc, "tableColumn");
            out.durationMinutes = cc.has("durationMinutes") && cc.get("durationMinutes").canConvertToInt()
                    ? cc.get("durationMinutes").asInt(90) : 90;
            out.autoAssignTable = cc.has("autoAssignTable") && cc.get("autoAssignTable").asBoolean(false);
            out.tables = new ArrayList<>();
            if (cc.has("tables") && cc.get("tables").isArray()) {
                for (JsonNode mn : cc.get("tables")) {
                    PanelTable mt = new PanelTable();
                    mt.id       = textOrNull(mn, "id");
                    mt.label    = textOrNull(mn, "label");
                    mt.capacity = mn.has("capacity") && mn.get("capacity").canConvertToInt()
                            ? mn.get("capacity").asInt(0) : 0;
                    if (mt.id != null && !mt.id.isBlank()) out.tables.add(mt);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[panel] No se pudo parsear panelConfigJson tabla={}: {}", t.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Detecta conflictos entre el record candidato y los records existentes
     * de la misma tabla.
     *
     * @param table         la BotTable
     * @param candidateData los datos del registro propuesto (incluye fecha, mesa, personas)
     * @param excludeRecordId si estamos editando, el id del registro que NO se cuenta como conflicto
     */
    public ConflictReport checkConflict(BotTable table,
                                        JsonNode candidateData,
                                        Long excludeRecordId) {
        ConflictReport rep = new ConflictReport();
        if (candidateData == null) return rep;

        CalendarCfg cfg = readCalendarCfg(table);
        if (cfg == null || cfg.dateColumn == null) return rep; // tabla sin calendario

        LocalDateTime candidateStart = parseDateTime(strField(candidateData, cfg.dateColumn));
        if (candidateStart == null) return rep; // sin fecha no chequeo
        LocalDateTime candidateEnd = candidateStart.plusMinutes(cfg.durationMinutes);

        String candidateTableId = cfg.tableColumn == null
                ? null : strField(candidateData, cfg.tableColumn);
        Integer candidatePersons = cfg.personsColumn == null
                ? null : intField(candidateData, cfg.personsColumn);

        // Cargar todos los records de la tabla. Para volúmenes < 5k está OK;
        // si crece mucho, agregar índice JSON por dateColumn.
        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        for (BotTableRecord r : all) {
            if (excludeRecordId != null && excludeRecordId.equals(r.getId())) continue;
            JsonNode data;
            try { data = objectMapper.readTree(r.getDataJson()); } catch (Exception ex) { continue; }
            LocalDateTime otherStart = parseDateTime(strField(data, cfg.dateColumn));
            if (otherStart == null) continue;
            LocalDateTime otherEnd = otherStart.plusMinutes(cfg.durationMinutes);

            // Solo nos interesan los del mismo día — si no se solapan en tiempo, descartar
            if (!overlaps(candidateStart, candidateEnd, otherStart, otherEnd)) continue;

            String otherTableId = cfg.tableColumn == null ? null : strField(data, cfg.tableColumn);

            // Si hay mesa: solo conflicto si es la MISMA mesa.
            // Si no hay mesa configurada: conflicto si se solapa el horario en absoluto.
            boolean conflict;
            if (cfg.tableColumn != null) {
                if (candidateTableId == null || candidateTableId.isBlank()) {
                    // candidato sin mesa → no conflicto explícito (se va a auto-asignar)
                    continue;
                }
                conflict = candidateTableId.equalsIgnoreCase(otherTableId);
            } else {
                conflict = true;
            }
            if (!conflict) continue;

            ConflictItem ci = new ConflictItem();
            ci.recordId  = r.getId();
            ci.tableId   = otherTableId;
            ci.tableLabel = labelForTable(cfg, otherTableId);
            ci.startsAt  = otherStart.toString();
            ci.endsAt    = otherEnd.toString();
            ci.summary   = buildSummary(data, cfg);
            rep.conflicts.add(ci);
        }

        rep.hasConflict = !rep.conflicts.isEmpty();

        // Si hay conflicto y autoAssign está activo, sugerir alternativa.
        if (rep.hasConflict && cfg.autoAssignTable && cfg.tableColumn != null && !cfg.tables.isEmpty()) {
            AutoAssignResult sugg = autoAssign(table, cfg, candidateStart, candidateEnd,
                    candidatePersons, excludeRecordId, all);
            if (sugg.assigned) {
                rep.suggestedTableId    = sugg.tableId;
                rep.suggestedTableLabel = sugg.tableLabel;
            }
        }

        if (rep.hasConflict) {
            String label = rep.conflicts.get(0).tableLabel;
            rep.message = label != null
                    ? "Ya hay una reserva en " + label + " a esa hora."
                    : "Ya hay una reserva ocupando ese horario.";
        }
        return rep;
    }

    /**
     * Auto-asigna la mesa más chica que (a) tiene capacidad >= personas y
     * (b) está libre en el rango horario propuesto. Si no se pasa allRecords,
     * se cargan.
     */
    public AutoAssignResult autoAssign(BotTable table,
                                       CalendarCfg cfg,
                                       LocalDateTime startAt,
                                       LocalDateTime endAt,
                                       Integer persons,
                                       Long excludeRecordId,
                                       List<BotTableRecord> allRecords) {
        AutoAssignResult res = new AutoAssignResult();
        if (cfg == null || cfg.tableColumn == null || cfg.tables.isEmpty()) {
            res.assigned = false;
            res.reason = "table_column_or_tables_not_configured";
            return res;
        }
        if (allRecords == null) {
            allRecords = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());
        }

        // Mesas elegibles: capacidad suficiente. Ordenadas por capacidad ascendente
        // para asignar la más chica que entra (eficiencia de uso).
        List<PanelTable> eligible = new ArrayList<>();
        for (PanelTable pt : cfg.tables) {
            if (persons == null || pt.capacity <= 0 || pt.capacity >= persons) eligible.add(pt);
        }
        eligible.sort(Comparator.comparingInt(p -> p.capacity > 0 ? p.capacity : Integer.MAX_VALUE));

        for (PanelTable pt : eligible) {
            boolean busy = false;
            for (BotTableRecord r : allRecords) {
                if (excludeRecordId != null && excludeRecordId.equals(r.getId())) continue;
                JsonNode data;
                try { data = objectMapper.readTree(r.getDataJson()); } catch (Exception ex) { continue; }
                String otherTableId = strField(data, cfg.tableColumn);
                if (otherTableId == null || !otherTableId.equalsIgnoreCase(pt.id)) continue;
                LocalDateTime otherStart = parseDateTime(strField(data, cfg.dateColumn));
                if (otherStart == null) continue;
                LocalDateTime otherEnd = otherStart.plusMinutes(cfg.durationMinutes);
                if (overlaps(startAt, endAt, otherStart, otherEnd)) { busy = true; break; }
            }
            if (!busy) {
                res.assigned = true;
                res.tableId = pt.id;
                res.tableLabel = pt.label != null ? pt.label : pt.id;
                return res;
            }
        }
        res.assigned = false;
        res.reason = "no_table_available";
        return res;
    }

    /**
     * Aplica auto-asignación al data del registro si corresponde.
     * Modifica `data` in-place (le setea el tableColumn) y devuelve la mesa elegida (o null).
     */
    public String maybeAutoAssign(BotTable table, ObjectNode data, Long excludeRecordId) {
        CalendarCfg cfg = readCalendarCfg(table);
        if (cfg == null || cfg.tableColumn == null || !cfg.autoAssignTable) return null;
        // Si ya tiene mesa, no tocar
        String existing = strField(data, cfg.tableColumn);
        if (existing != null && !existing.isBlank()) return existing;
        if (cfg.dateColumn == null) return null;
        LocalDateTime start = parseDateTime(strField(data, cfg.dateColumn));
        if (start == null) return null;
        LocalDateTime end = start.plusMinutes(cfg.durationMinutes);
        Integer persons = cfg.personsColumn == null ? null : intField(data, cfg.personsColumn);
        AutoAssignResult r = autoAssign(table, cfg, start, end, persons, excludeRecordId, null);
        if (r.assigned) {
            data.put(cfg.tableColumn, r.tableId);
            return r.tableId;
        }
        return null;
    }

    /** Devuelve el "estado" de cada mesa en el momento dado, para el sidebar live. */
    public List<Map<String,Object>> tableStatusAt(BotTable table, LocalDateTime at) {
        CalendarCfg cfg = readCalendarCfg(table);
        List<Map<String,Object>> out = new ArrayList<>();
        if (cfg == null || cfg.tableColumn == null) return out;
        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());
        for (PanelTable pt : cfg.tables) {
            Map<String,Object> row = new java.util.HashMap<>();
            row.put("id", pt.id);
            row.put("label", pt.label != null ? pt.label : pt.id);
            row.put("capacity", pt.capacity);
            // Buscar reserva activa
            String state = "free";
            String currentLabel = null;
            Long currentRecordId = null;
            String currentEndsAt = null;
            for (BotTableRecord r : all) {
                JsonNode data;
                try { data = objectMapper.readTree(r.getDataJson()); } catch (Exception ex) { continue; }
                String otherTableId = strField(data, cfg.tableColumn);
                if (otherTableId == null || !otherTableId.equalsIgnoreCase(pt.id)) continue;
                LocalDateTime otherStart = parseDateTime(strField(data, cfg.dateColumn));
                if (otherStart == null) continue;
                LocalDateTime otherEnd = otherStart.plusMinutes(cfg.durationMinutes);
                if (!at.isBefore(otherStart) && at.isBefore(otherEnd)) {
                    state = "occupied";
                    currentLabel = buildSummary(data, cfg);
                    currentRecordId = r.getId();
                    currentEndsAt = otherEnd.toString();
                    break;
                }
            }
            row.put("state", state);
            row.put("currentLabel", currentLabel);
            row.put("currentRecordId", currentRecordId);
            row.put("currentEndsAt", currentEndsAt);
            out.add(row);
        }
        return out;
    }

    // ─────── helpers ───────

    private static boolean overlaps(LocalDateTime a1, LocalDateTime a2,
                                    LocalDateTime b1, LocalDateTime b2) {
        return a1.isBefore(b2) && b1.isBefore(a2);
    }

    private static String textOrNull(JsonNode n, String f) {
        if (n == null || !n.has(f) || n.get(f).isNull()) return null;
        String s = n.get(f).asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String strField(JsonNode data, String field) {
        if (data == null || field == null || !data.has(field)) return null;
        JsonNode n = data.get(field);
        if (n == null || n.isNull()) return null;
        return n.asText();
    }

    private static Integer intField(JsonNode data, String field) {
        if (data == null || field == null || !data.has(field)) return null;
        JsonNode n = data.get(field);
        if (n == null || n.isNull()) return null;
        if (n.canConvertToInt()) return n.asInt();
        try { return Integer.parseInt(n.asText().trim()); } catch (Exception e) { return null; }
    }

    /** Acepta yyyy-MM-dd, yyyy-MM-ddTHH:mm, yyyy-MM-ddTHH:mm:ss, etc. */
    public static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        // Si solo es fecha, asumimos medianoche
        if (s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            try { return LocalDate.parse(s).atStartOfDay(); } catch (Exception e) { return null; }
        }
        try { return LocalDateTime.parse(s); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (Exception ignored) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
        catch (Exception ignored) {}
        // yyyy-MM-ddTHH:mm con segundos
        try { return LocalDateTime.parse(s.replace(' ', 'T')); } catch (Exception ignored) {}
        return null;
    }

    private String labelForTable(CalendarCfg cfg, String id) {
        if (id == null || cfg == null) return id;
        return cfg.tables.stream()
                .filter(p -> id.equalsIgnoreCase(p.id))
                .map(p -> p.label != null ? p.label : p.id)
                .findFirst().orElse(id);
    }

    private String buildSummary(JsonNode data, CalendarCfg cfg) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        if (cfg.titleColumn != null) {
            String t = strField(data, cfg.titleColumn);
            if (t != null) sb.append(t);
        }
        if (cfg.personsColumn != null) {
            Integer p = intField(data, cfg.personsColumn);
            if (p != null) sb.append(sb.length() > 0 ? " · " : "").append(p).append(" pers");
        }
        return sb.toString();
    }

    // ─────── DTOs internos ───────
    public static class CalendarCfg {
        public String dateColumn;
        public String titleColumn;
        public String statusColumn;
        public String personsColumn;
        public String tableColumn;
        public int durationMinutes = 90;
        public boolean autoAssignTable = false;
        public List<PanelTable> tables = new ArrayList<>();
    }

    public static class PanelTable {
        public String id;
        public String label;
        public int capacity;
    }
}
