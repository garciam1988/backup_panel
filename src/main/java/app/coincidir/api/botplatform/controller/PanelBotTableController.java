package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.service.BotTableChangeEvent;
import app.coincidir.api.botplatform.service.BotTableService;
import app.coincidir.api.botplatform.service.PanelBotTableService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * PanelBotTableController — endpoints del /panel para operar sobre las
 * tablas custom del bot que tienen panelConfig.enabled=true.
 *
 * Reusa BotTableService para validar/normalizar registros (mismo schema y
 * mismos eventos que el flujo de admin), pero está bajo /api/panel/** para
 * que el JWT del operador (no admin) pueda acceder.
 *
 *   GET    /api/panel/bot-tables                       (lista tablas con panel habilitado)
 *   GET    /api/panel/bot-tables/{id}                  (detalle + columnas + panelConfig)
 *   GET    /api/panel/bot-tables/{id}/records          ?from=&to=&status=&q=
 *   POST   /api/panel/bot-tables/{id}/records          (crea, con auto-asignación de mesa si aplica)
 *   PUT    /api/panel/bot-tables/records/{recordId}    (edita merge)
 *   DELETE /api/panel/bot-tables/records/{recordId}
 *   POST   /api/panel/bot-tables/{id}/conflict-check   (check sin guardar)
 *   GET    /api/panel/bot-tables/{id}/table-status     ?at=ISO  (estado de mesas en un momento)
 *   GET    /api/panel/bot-tables/{id}/stats            ?from=&to=  (agregaciones para gráficos)
 */
@Slf4j
@RestController
@RequestMapping("/api/panel/bot-tables")
@RequiredArgsConstructor
public class PanelBotTableController {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final BotTableService service;
    private final PanelBotTableService panelService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────── Tablas (sólo las que tienen panel habilitado) ───────

    @GetMapping
    @Transactional(readOnly = true)
    public List<TablePanelDto> list() {
        List<TablePanelDto> out = new ArrayList<>();
        for (BotTable t : tableRepo.findAllByOrderByNameAsc()) {
            if (!Boolean.TRUE.equals(t.getActive())) continue;
            JsonNode pc = parsePanelCfg(t);
            if (pc == null || !pc.path("enabled").asBoolean(false)) continue;
            out.add(toPanelDto(t, pc));
        }
        return out;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TablePanelDto get(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        JsonNode pc = parsePanelCfg(t);
        if (pc == null || !pc.path("enabled").asBoolean(false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta tabla no tiene panel habilitado");
        }
        return toPanelDto(t, pc);
    }

    // ─────── Records ───────

    @GetMapping("/{id}/records")
    @Transactional(readOnly = true)
    public List<RecordDto> listRecords(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        BotTable t = requirePanelEnabled(id);
        PanelBotTableService.CalendarCfg cfg = panelService.readCalendarCfg(t);

        LocalDateTime fromDt = parseFlexible(from);
        LocalDateTime toDt   = parseFlexible(to);
        // Si "to" es solo fecha, lo extendemos a fin de día
        if (to != null && to.length() == 10 && toDt != null) {
            toDt = toDt.toLocalDate().atTime(23, 59, 59);
        }

        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        List<RecordDto> out = new ArrayList<>();
        for (BotTableRecord r : all) {
            JsonNode data;
            try { data = objectMapper.readTree(r.getDataJson()); } catch (Exception ex) { continue; }

            // Filtro fecha (si tabla tiene calendario)
            if (cfg != null && cfg.dateColumn != null && (fromDt != null || toDt != null)) {
                String s = textField(data, cfg.dateColumn);
                LocalDateTime recDt = PanelBotTableService.parseDateTime(s);
                if (recDt == null) continue;
                if (fromDt != null && recDt.isBefore(fromDt)) continue;
                if (toDt   != null && recDt.isAfter(toDt))    continue;
            }

            // Filtro status
            if (status != null && !status.isBlank() && cfg != null && cfg.statusColumn != null) {
                String rs = textField(data, cfg.statusColumn);
                if (rs == null || !rs.equalsIgnoreCase(status)) continue;
            }

            // Filtro free text
            if (q != null && !q.isBlank()) {
                if (!data.toString().toLowerCase().contains(q.toLowerCase())) continue;
            }

            out.add(toRecordDto(r));
        }
        return out;
    }

    @PostMapping("/{id}/records")
    @Transactional
    public RecordSaveResponse createRecord(@PathVariable Long id,
                                           @RequestBody RecordSaveRequest req) {
        BotTable t = requirePanelEnabled(id);
        if (req == null || req.data == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data requerido");

        ObjectNode dataMut = (req.data instanceof ObjectNode) ? (ObjectNode) req.data
                : objectMapper.createObjectNode();
        if (!(req.data instanceof ObjectNode)) {
            ((ObjectNode) dataMut).setAll((ObjectNode) req.data);
        }

        // Auto-asignar mesa si aplica y no la trae cargada
        String autoAssigned = panelService.maybeAutoAssign(t, dataMut, null);

        // Validar conflicto antes de guardar
        PanelBotTableService.ConflictReport conflict =
                panelService.checkConflict(t, dataMut, null);

        if (conflict.hasConflict && Boolean.FALSE.equals(req.force)) {
            RecordSaveResponse rsp = new RecordSaveResponse();
            rsp.conflict = conflict;
            return rsp;
        }

        try {
            String normalized = service.validateAndNormalizeRecord(t, dataMut);
            BotTableRecord rec = new BotTableRecord();
            rec.setTableId(t.getId());
            rec.setDataJson(normalized);
            rec.setSource("admin"); // creado desde panel ≈ admin
            rec = recordRepo.save(rec);
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "created")); } catch (Exception ignored) {}

            RecordSaveResponse rsp = new RecordSaveResponse();
            rsp.record = toRecordDto(rec);
            rsp.autoAssignedTableId = autoAssigned;
            return rsp;
        } catch (BotTableService.SchemaError e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/records/{recordId}")
    @Transactional
    public RecordSaveResponse updateRecord(@PathVariable Long recordId,
                                           @RequestBody RecordSaveRequest req) {
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BotTable t = requirePanelEnabled(rec.getTableId());

        try {
            // Merge con dataJson actual
            ObjectNode current;
            try {
                JsonNode parsed = objectMapper.readTree(rec.getDataJson());
                current = parsed instanceof ObjectNode ? (ObjectNode) parsed : objectMapper.createObjectNode();
            } catch (Exception ex) {
                current = objectMapper.createObjectNode();
            }
            if (req != null && req.data != null && req.data.isObject()) {
                current.setAll((ObjectNode) req.data);
            }

            // Validar conflicto (excluyendo este record)
            PanelBotTableService.ConflictReport conflict =
                    panelService.checkConflict(t, current, recordId);
            if (conflict.hasConflict && (req == null || !Boolean.TRUE.equals(req.force))) {
                RecordSaveResponse rsp = new RecordSaveResponse();
                rsp.conflict = conflict;
                return rsp;
            }

            String normalized = service.validateAndNormalizeRecord(t, current);
            rec.setDataJson(normalized);
            rec = recordRepo.save(rec);
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "updated")); } catch (Exception ignored) {}

            RecordSaveResponse rsp = new RecordSaveResponse();
            rsp.record = toRecordDto(rec);
            return rsp;
        } catch (BotTableService.SchemaError e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/records/{recordId}")
    @Transactional
    public void deleteRecord(@PathVariable Long recordId) {
        Optional<BotTableRecord> opt = recordRepo.findById(recordId);
        if (opt.isEmpty()) return;
        BotTableRecord rec = opt.get();
        BotTable t = tableRepo.findById(rec.getTableId()).orElse(null);
        if (t != null) {
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "deleted")); } catch (Exception ignored) {}
        }
        recordRepo.deleteById(recordId);
    }

    // ─────── Conflict check (sin guardar) ───────

    @PostMapping("/{id}/conflict-check")
    @Transactional(readOnly = true)
    public PanelBotTableService.ConflictReport conflictCheck(@PathVariable Long id,
                                                              @RequestBody ConflictCheckRequest req) {
        BotTable t = requirePanelEnabled(id);
        return panelService.checkConflict(t, req.data, req.recordId);
    }

    // ─────── Estado live de mesas ───────

    @GetMapping("/{id}/table-status")
    @Transactional(readOnly = true)
    public List<Map<String,Object>> tableStatus(@PathVariable Long id,
                                                @RequestParam(required = false) String at) {
        BotTable t = requirePanelEnabled(id);
        LocalDateTime moment = at != null ? parseFlexible(at) : LocalDateTime.now();
        if (moment == null) moment = LocalDateTime.now();
        return panelService.tableStatusAt(t, moment);
    }

    // ─────── Stats / agregaciones ───────

    @GetMapping("/{id}/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats(@PathVariable Long id,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        BotTable t = requirePanelEnabled(id);
        PanelBotTableService.CalendarCfg cfg = panelService.readCalendarCfg(t);

        LocalDateTime fromDt = parseFlexible(from);
        LocalDateTime toDt   = parseFlexible(to);
        if (to != null && to.length() == 10 && toDt != null) toDt = toDt.toLocalDate().atTime(23, 59, 59);

        List<BotTableRecord> all = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
        long total = 0;
        Map<String, Long> byDay = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long sumPersons = 0;

        for (BotTableRecord r : all) {
            JsonNode data;
            try { data = objectMapper.readTree(r.getDataJson()); } catch (Exception ex) { continue; }
            LocalDateTime dt = null;
            if (cfg != null && cfg.dateColumn != null) {
                dt = PanelBotTableService.parseDateTime(textField(data, cfg.dateColumn));
            }
            if (cfg != null && cfg.dateColumn != null) {
                if (dt == null) continue;
                if (fromDt != null && dt.isBefore(fromDt)) continue;
                if (toDt   != null && dt.isAfter(toDt))    continue;
            }

            total++;

            if (dt != null) {
                String day = dt.toLocalDate().toString();
                byDay.merge(day, 1L, Long::sum);
            }
            if (cfg != null && cfg.statusColumn != null) {
                String s = textField(data, cfg.statusColumn);
                if (s != null && !s.isBlank()) byStatus.merge(s, 1L, Long::sum);
            }
            if (cfg != null && cfg.personsColumn != null) {
                Integer p = intField(data, cfg.personsColumn);
                if (p != null) sumPersons += p;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byDay", byDay);
        out.put("byStatus", byStatus);
        out.put("sumPersons", sumPersons);
        return out;
    }

    // ─────── helpers ───────

    private BotTable requirePanelEnabled(Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        JsonNode pc = parsePanelCfg(t);
        if (pc == null || !pc.path("enabled").asBoolean(false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "panel no habilitado para esta tabla");
        }
        return t;
    }

    private JsonNode parsePanelCfg(BotTable t) {
        if (t.getPanelConfigJson() == null || t.getPanelConfigJson().isBlank()) return null;
        try { return objectMapper.readTree(t.getPanelConfigJson()); }
        catch (Exception e) { return null; }
    }

    private LocalDateTime parseFlexible(String s) {
        if (s == null || s.isBlank()) return null;
        return PanelBotTableService.parseDateTime(s);
    }

    private static String textField(JsonNode n, String f) {
        if (n == null || f == null || !n.has(f) || n.get(f).isNull()) return null;
        String v = n.get(f).asText();
        return (v == null || v.isBlank()) ? null : v;
    }
    private static Integer intField(JsonNode n, String f) {
        if (n == null || f == null || !n.has(f) || n.get(f).isNull()) return null;
        JsonNode x = n.get(f);
        if (x.canConvertToInt()) return x.asInt();
        try { return Integer.parseInt(x.asText().trim()); } catch (Exception e) { return null; }
    }

    // ─────── DTOs ───────

    private TablePanelDto toPanelDto(BotTable t, JsonNode pc) {
        TablePanelDto d = new TablePanelDto();
        d.id = t.getId();
        d.slug = t.getSlug();
        d.name = t.getName();
        d.description = t.getDescription();
        d.columnsJson = t.getColumnsJson();
        d.panelConfigJson = t.getPanelConfigJson();
        return d;
    }

    private RecordDto toRecordDto(BotTableRecord r) {
        RecordDto d = new RecordDto();
        d.id = r.getId();
        d.tableId = r.getTableId();
        try { d.data = objectMapper.readTree(r.getDataJson()); } catch (Exception ignored) {}
        d.source = r.getSource();
        d.createdAt = r.getCreatedAt();
        d.updatedAt = r.getUpdatedAt();
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TablePanelDto {
        public Long id;
        public String slug;
        public String name;
        public String description;
        public String columnsJson;
        public String panelConfigJson;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordDto {
        public Long id;
        public Long tableId;
        public JsonNode data;
        public String source;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordSaveRequest {
        public JsonNode data;
        /** Si true, ignora conflictos y guarda igual. */
        public Boolean force;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordSaveResponse {
        /** Si hay conflicto y force=false, viene esto y record null. */
        public PanelBotTableService.ConflictReport conflict;
        public RecordDto record;
        /** Mesa que se asignó automáticamente al crear (si aplica). */
        public String autoAssignedTableId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConflictCheckRequest {
        public JsonNode data;
        public Long recordId;
    }
}
