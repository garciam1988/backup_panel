package app.coincidir.api.botplatform.controller;

import app.coincidir.api.audit.domain.AuditLog;
import app.coincidir.api.audit.repository.AuditLogRepository;
import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.service.BotTableChangeEvent;
import app.coincidir.api.botplatform.service.BotTableService;
import app.coincidir.api.botplatform.service.PanelBotTableService;
import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.ConversationLogRepository;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
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
    private final ConversationLogRepository conversationLogRepo;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepo;
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

        // Tenancy: filtramos por la branch del contexto del request.
        //   - User no-DIOS: el BranchResolverFilter resolvió su única branch
        //     (o la preferida) y la metió en el ThreadLocal. Solo ve la suya.
        //   - DIOS sin elegir sucursal: scope=null, mostramos TODO (modo marca).
        //   - DIOS con sucursal elegida: scope a esa branch, ve solo eso.
        BranchScope scope = BranchContext.current();
        Long branchFilter = (scope != null) ? scope.getBranchId() : null;

        List<BotTableRecord> all = (branchFilter != null)
                ? recordRepo.findByTableIdAndBranchIdOrderByCreatedAtDesc(t.getId(), branchFilter)
                : recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());

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

            // Tenancy: el record nace en la branch del contexto del request.
            // Para users no-DIOS, esa es la branch del JWT (resuelta por el
            // BranchResolverFilter). Para DIOS con branch elegida, esa branch.
            // Si DIOS no eligió (scope=null), el record queda sin branch — eso
            // es raro y debería evitarse en UI, pero técnicamente vale.
            BranchScope scopeCreate = BranchContext.current();
            if (scopeCreate != null) {
                rec.setBranchId(scopeCreate.getBranchId());
            } else {
                log.warn("[Panel] createRecord sin branch en contexto — record id quedará sin atribución (tableId={})", t.getId());
            }

            rec = recordRepo.save(rec);
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "created")); } catch (Exception ignored) {}

            // Audit: registrar la creación en /reserve.
            // Para reservas, el "entityType" más descriptivo es el nombre de la
            // tabla (singular si se puede). Usamos el nombre de la BotTable.
            try {
                Map<String, Object> snap = jsonToMap(normalized);
                auditService.logCreate(
                    actionKey(t, "create"),
                    entityTypeOf(t),
                    String.valueOf(rec.getId()),
                    buildEntityLabel(t, snap),
                    "reserve",
                    snap
                );
            } catch (Exception ignored) {}

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
        requireSameBranch(rec, "actualizar");
        BotTable t = requirePanelEnabled(rec.getTableId());

        // Snapshot del estado PREVIO para el diff de auditoría. Lo tomamos
        // antes de mergear cambios para que reflje exactamente lo que había.
        Map<String, Object> auditOldSnap;
        try { auditOldSnap = jsonToMap(rec.getDataJson()); }
        catch (Exception e) { auditOldSnap = Collections.emptyMap(); }

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

            // Re-aplicar columnas auto (auto: true en el schema) DESPUÉS del save.
            // Esto cubre el caso típico de `fecha_display` con autoTemplate que
            // depende de `fecha_y_hora_reserva`: cuando el operador arrastra una
            // reserva en el calendario y cambia la fecha, también tiene que
            // regenerarse el display, si no las notificaciones y la columna
            // FECHA_DISPLAY de la tabla quedan con valores stale.
            //
            // BotTableService.doUpdate (endpoint del bot público) ya hace esto;
            // este endpoint del panel se había olvidado y por eso aparecía un bug
            // donde mover reservas dejaba fecha_display desactualizada.
            //
            // applyAutoColumns es idempotente y cheap (no hace I/O), así que lo
            // corremos siempre. Solo hace save extra si algo realmente cambió.
            try {
                String withAuto = service.applyAutoColumns(t, rec);
                if (!withAuto.equals(rec.getDataJson())) {
                    rec.setDataJson(withAuto);
                    rec = recordRepo.save(rec);
                    // Re-normalizamos para que el response refleje los valores auto.
                    normalized = withAuto;
                }
            } catch (Exception autoEx) {
                // Best-effort: si la regeneración auto falla, dejamos el save
                // principal igual confirmado. Solo loggeamos.
                log.warn("[Panel] applyAutoColumns falló en update record {}: {}",
                        rec.getId(), autoEx.getMessage());
            }

            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "updated")); } catch (Exception ignored) {}

            // Audit: calculamos el diff entre el snapshot previo y el nuevo.
            // Si el único cambio es la columna de estado (single-column update),
            // usamos un summary específico ("Cambió estado de X a Y") en vez del
            // diff genérico — esto es la operación más común del CAJA.
            try {
                Map<String, Object> auditNewSnap = jsonToMap(normalized);
                String statusCol = extractStatusColumn(t);
                boolean statusOnlyChange = isStatusOnlyChange(auditOldSnap, auditNewSnap, statusCol);
                if (statusOnlyChange) {
                    Object oldSt = auditOldSnap.get(statusCol);
                    Object newSt = auditNewSnap.get(statusCol);
                    String summary = "Cambió estado de " +
                        (oldSt != null ? oldSt : "sin asignar") +
                        " a " + (newSt != null ? newSt : "sin asignar");
                    auditService.logActionWithChanges(
                        actionKey(t, "status_change"),
                        entityTypeOf(t),
                        String.valueOf(rec.getId()),
                        buildEntityLabel(t, auditNewSnap),
                        "reserve",
                        summary,
                        auditOldSnap,
                        auditNewSnap
                    );
                } else {
                    auditService.logUpdate(
                        actionKey(t, "update"),
                        entityTypeOf(t),
                        String.valueOf(rec.getId()),
                        buildEntityLabel(t, auditNewSnap),
                        "reserve",
                        auditOldSnap,
                        auditNewSnap
                    );
                }
            } catch (Exception ignored) {}

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
        requireSameBranch(rec, "eliminar");
        BotTable t = tableRepo.findById(rec.getTableId()).orElse(null);

        // Snapshot del estado previo para el audit log (antes de borrar).
        Map<String, Object> auditOldSnap;
        try { auditOldSnap = jsonToMap(rec.getDataJson()); }
        catch (Exception e) { auditOldSnap = Collections.emptyMap(); }
        String auditLabel = t != null ? buildEntityLabel(t, auditOldSnap) : null;
        String auditEntityType = t != null ? entityTypeOf(t) : "Record";
        String auditAction = t != null ? actionKey(t, "delete") : "record.delete";

        if (t != null) {
            // Disparamos "cancelled" (no "deleted") por consistencia con las otras
            // rutas de borrado: BotTableService.deleteRecord() (cuando el bot
            // ejecuta delete_record) y BotTableAdminController (cuando el admin
            // borra desde /admin > tablas) ambas usan "cancelled".
            // Si acá usáramos "deleted", el EmailTemplate configurado bajo
            // event="cancelled" en /admin > config emails > "Al cancelar" no
            // matchearía y el mail al cliente NO saldría cuando un operador
            // borra desde el panel.
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "cancelled")); } catch (Exception ignored) {}
        }
        recordRepo.deleteById(recordId);

        // Audit: registramos la eliminación con el snapshot del estado previo.
        try {
            auditService.logDelete(
                auditAction,
                auditEntityType,
                String.valueOf(recordId),
                auditLabel,
                "reserve",
                auditOldSnap
            );
        } catch (Exception ignored) {}
    }

    // ─────── Conversación del bot asociada al record ───────

    /**
     * Devuelve la conversación del chat que creó el record (si la hay).
     *
     * Cómo se hace el matching:
     *  - {@code bot_table_record.session_id} se setea cuando el bot ejecuta
     *    add_record durante una sesión de chat (ver BotTableService.doAdd).
     *  - El frontend del bot manda el mismo sessionId al cerrar la charla
     *    como {@code visitor_id} en conversation_log (ver CoinBot.jsx, donde
     *    visitorId = sessionIdRef.current).
     *  - Por eso podemos joinearlos: record.session_id ↔ conversation_log.visitor_id.
     *
     * Si la charla todavía está activa (el cliente sigue tipeando), el
     * conversation_log puede no existir aún — recién se persiste en el
     * cierre (timeout, beforeunload, manual). En ese caso devolvemos
     * 404 con un mensaje claro para que el frontend muestre algo amable.
     *
     * Si el record fue creado manualmente desde /admin o /panel (source != "bot"),
     * no tiene session_id y también devolvemos 404.
     */
    @GetMapping("/{tableId}/records/{recordId}/conversation")
    @Transactional(readOnly = true)
    public ConversationDto getRecordConversation(@PathVariable Long tableId,
                                                 @PathVariable Long recordId) {
        // 1) Validamos que la tabla exista y tenga panel habilitado (consistencia
        //    con el resto de endpoints del panel).
        BotTable t = requirePanelEnabled(tableId);

        // 2) Cargamos el record.
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record no encontrado"));
        if (!Objects.equals(rec.getTableId(), t.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El record no pertenece a esta tabla");
        }
        requireSameBranch(rec, "consultar la conversación de");

        // 3) Si el record no tiene session_id, no hay chat asociado.
        String sessionId = rec.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Esta reserva no fue creada por el bot — no hay conversación asociada.");
        }

        // 4) Buscamos el conversation_log por visitorId = sessionId.
        Optional<ConversationLog> optLog = conversationLogRepo.findFirstByVisitorIdOrderByIdDesc(sessionId);
        if (optLog.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "La conversación todavía está en curso o aún no fue registrada. Volvé a intentar más tarde.");
        }

        ConversationLog log = optLog.get();
        ConversationDto dto = new ConversationDto();
        dto.sessionId = sessionId;
        dto.startedAt = log.getStartedAt();
        dto.endedAt = log.getEndedAt();
        dto.messageCount = log.getMessageCount();
        dto.closedReason = log.getClosedReason();
        dto.deviceType = log.getDeviceType();
        dto.deviceOs = log.getDeviceOs();
        dto.deviceBrowser = log.getDeviceBrowser();
        dto.clientFirstName = log.getClientFirstName();
        dto.clientLastName = log.getClientLastName();
        // El messages_json ya está en formato JSON array — lo pasamos crudo
        // al frontend que lo parsea. Si por algún motivo está vacío, devolvemos
        // "[]" para que el frontend no se rompa al parsear.
        dto.messagesJson = (log.getMessagesJson() == null || log.getMessagesJson().isBlank())
                ? "[]"
                : log.getMessagesJson();
        return dto;
    }

    // ─────── Historial de auditoría del record ───────

    /**
     * Devuelve los logs de auditoría asociados a este record (cualquier
     * acción que el módulo audit haya capturado: status_change, update,
     * create, delete pasados, etc.).
     *
     * Se usa para mostrar la tab "Histórico" del modal de reserva en
     * /reserve, con la trazabilidad completa de quién y cuándo tocó la
     * reserva.
     *
     * Cap defensivo a 50 entries — es más que suficiente para una reserva
     * (en la práctica suelen ser 1-5 eventos). Si por algún motivo hay
     * más, el frontend solo muestra los más recientes.
     *
     * Sin filtro de permisos: cualquier usuario que puede acceder al
     * /reserve puede ver esta info (es sobre la reserva misma, no sobre
     * el sistema). Para auditoría profunda con diff, el operador usa el
     * módulo /admin → Auditoría.
     */
    @GetMapping("/{tableId}/records/{recordId}/history")
    @Transactional(readOnly = true)
    public java.util.List<HistoryEventDto> getRecordHistory(@PathVariable Long tableId,
                                                            @PathVariable Long recordId) {
        // Validar que la tabla exista y tenga panel habilitado
        BotTable t = requirePanelEnabled(tableId);

        // Verificar que el record exista y pertenezca a esta tabla.
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record no encontrado"));
        if (!Objects.equals(rec.getTableId(), t.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El record no pertenece a esta tabla");
        }
        requireSameBranch(rec, "ver el historial de");

        // entityType en el audit log es el nombre de la BotTable (ej "Reservas").
        // Lo pasamos para filtrar más preciso aunque entityId ya sea único.
        String entityType = t.getName();

        java.util.List<AuditLog> logs = auditLogRepo.findByEntity(
            String.valueOf(recordId),
            entityType,
            PageRequest.of(0, 50)
        );

        // Mapear a DTO acotado — el frontend no necesita el diff completo
        // (el caller pidió "sin diff", solo quién/cuándo/qué acción).
        java.util.List<HistoryEventDto> out = new java.util.ArrayList<>(logs.size());
        for (AuditLog l : logs) {
            HistoryEventDto d = new HistoryEventDto();
            d.id = l.getId();
            d.ts = l.getTs();
            d.action = l.getAction();
            d.summary = l.getSummary();
            d.username = l.getUsername();
            d.displayName = l.getDisplayName();
            d.role = l.getRole();
            d.module = l.getModule();
            out.add(d);
        }
        return out;
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

        // Tenancy: stats también deben respetar la branch del contexto.
        // Si no, el gerente de Villa Crespo vería el dashboard global.
        BranchScope scope = BranchContext.current();
        Long branchFilter = (scope != null) ? scope.getBranchId() : null;

        List<BotTableRecord> all = (branchFilter != null)
                ? recordRepo.findByTableIdAndBranchIdOrderByCreatedAtDesc(t.getId(), branchFilter)
                : recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
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

    /**
     * Valida que un record sea de la branch del contexto del request.
     * Bloquea operaciones cross-branch en endpoints de detalle (GET, PUT,
     * DELETE de records por id) que de otro modo permitirían ver/editar
     * records de OTRAS sucursales si el caller adivina el ID.
     *
     * Reglas:
     *   - Si scope=null (DIOS modo marca sin elegir): no bloquea — dejamos pasar.
     *   - Si record.branchId=null (record legacy sin atribución): no bloquea —
     *     son datos viejos accesibles desde cualquier branch.
     *   - Si ambos están seteados y NO coinciden: 404 (no exponemos data ajena).
     *
     * @param rec el record a validar
     * @param verb verbo de la acción para el log (ej: "actualizar", "eliminar")
     */
    private void requireSameBranch(BotTableRecord rec, String verb) {
        BranchScope scope = BranchContext.current();
        if (scope == null) return;
        Long recBranch = rec.getBranchId();
        if (recBranch == null) return;
        if (!recBranch.equals(scope.getBranchId())) {
            log.warn("[Panel] Usuario en branch={} intentó {} record id={} de branch={} — bloqueado",
                    scope.getBranchId(), verb, rec.getId(), recBranch);
            // 404 (no 403) para no filtrar "existe pero no podés tocarlo".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

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

    // ─────────────────────────────────────────────────────────────────────
    // Helpers de auditoría (privados a este controller)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Convierte el data_json de un record en Map para el audit log.
     * Si el parse falla, devuelve mapa vacío en lugar de excepción —
     * el audit no debe romper la operación principal.
     */
    private Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            JsonNode n = objectMapper.readTree(json);
            if (n == null || !n.isObject()) return Collections.emptyMap();
            return objectMapper.convertValue(n, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Arma la clave de acción para el audit: {slugSingular}.{verbo}.
     * Ej: tabla "reservas" + "update" → "reservation.update".
     * Si no podemos singularizar, dejamos el slug tal cual.
     */
    private String actionKey(BotTable t, String verb) {
        String slug = t.getSlug() != null ? t.getSlug() : "record";
        // Simplificación: quitamos "s" final si termina en eso, para que
        // "reservas" → "reserva". Es un hack pero suficiente para Brasas.
        // Si quisiéramos algo más prolijo, agregaríamos un campo
        // `singularName` en BotTable.
        if (slug.length() > 2 && slug.endsWith("s")) slug = slug.substring(0, slug.length() - 1);
        return slug + "." + verb;
    }

    /** EntityType para el audit, derivado del nombre de la tabla. */
    private String entityTypeOf(BotTable t) {
        if (t == null) return "Record";
        return t.getName() != null && !t.getName().isBlank() ? t.getName() : "Record";
    }

    /**
     * Arma una etiqueta legible para el log. Intenta tomar el primer campo
     * "tipo nombre" (Nombre y Apellido, nombre, etc.) + un dato secundario
     * útil (fecha si la tabla es de reservas).
     *
     * Si no encontramos buenos campos, fallback al ID.
     */
    private String buildEntityLabel(BotTable t, Map<String, Object> snap) {
        if (snap == null || snap.isEmpty()) return null;
        String name = pickFirstNonBlank(snap,
            "Nombre y Apellido", "Nombre Y Apellido", "nombre", "name",
            "Cliente", "cliente", "Razón Social"
        );
        String date = pickFirstNonBlank(snap,
            "fecha_display", "Fecha Display", "Fecha y Hora Reserva", "fecha", "Fecha"
        );
        String parts;
        if (name != null && date != null) parts = name + " — " + date;
        else if (name != null) parts = name;
        else if (date != null) parts = date;
        else parts = null;
        return parts;
    }

    /** Busca la primera key que tenga valor no vacío entre las opciones. */
    private String pickFirstNonBlank(Map<String, Object> snap, String... keys) {
        for (String k : keys) {
            Object v = snap.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return null;
    }

    /** Extrae la columna de estado del panelConfig de la tabla, o null. */
    private String extractStatusColumn(BotTable t) {
        if (t == null || t.getPanelConfigJson() == null) return null;
        try {
            JsonNode cfg = objectMapper.readTree(t.getPanelConfigJson());
            JsonNode cal = cfg.path("calendarConfig");
            String s = cal.path("statusColumn").asText("");
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ¿El cambio entre old y new es SOLO en la columna de estado?
     * Si sí, el audit usa un summary específico tipo "Cambió estado de X a Y".
     */
    private boolean isStatusOnlyChange(Map<String, Object> oldSnap, Map<String, Object> newSnap, String statusCol) {
        if (statusCol == null || oldSnap == null || newSnap == null) return false;
        Set<String> changed = new java.util.HashSet<>();
        Set<String> allKeys = new java.util.HashSet<>();
        allKeys.addAll(oldSnap.keySet());
        allKeys.addAll(newSnap.keySet());
        for (String k : allKeys) {
            if (!Objects.equals(oldSnap.get(k), newSnap.get(k))) changed.add(k);
        }
        return changed.size() == 1 && changed.contains(statusCol);
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

    /**
     * DTO de la conversación asociada a un record. messagesJson llega como
     * string JSON (array) ya serializado por la entity — el frontend lo
     * parsea con JSON.parse(). El resto son metadatos para mostrar header
     * informativo en el modal.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConversationDto {
        public String sessionId;
        public Instant startedAt;
        public Instant endedAt;
        public Integer messageCount;
        public String closedReason;   // timeout | beforeunload | manual | tool_*
        public String deviceType;     // desktop | mobile | tablet
        public String deviceOs;
        public String deviceBrowser;
        public String clientFirstName;
        public String clientLastName;
        public String messagesJson;   // JSON array crudo, frontend lo parsea
    }

    /**
     * DTO para un evento de historial (audit log filtrado por entidad).
     * Más liviano que AuditLogDetailDto: no incluye changesJson ni IP, que
     * son para uso del módulo de auditoría completo en /admin.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HistoryEventDto {
        public Long id;
        public Instant ts;
        public String action;        // ej "reserva.status_change"
        public String summary;       // ej "Cambió estado de PENDIENTE a CONFIRMADA"
        public String username;      // login del usuario
        public String displayName;   // nombre humano (si existe)
        public String role;          // rol al momento (GERENTE, CAJA, DIOS)
        public String module;        // "reserve" | "admin" | etc.
    }
}
