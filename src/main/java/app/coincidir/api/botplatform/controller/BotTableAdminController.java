package app.coincidir.api.botplatform.controller;

import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.EmailLog;
import app.coincidir.api.botplatform.domain.EmailTemplate;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.repository.EmailLogRepository;
import app.coincidir.api.botplatform.repository.EmailTemplateRepository;
import app.coincidir.api.botplatform.service.BotTableChangeEvent;
import app.coincidir.api.botplatform.service.BotTableEmailService;
import app.coincidir.api.botplatform.service.BotTableImportExportService;
import app.coincidir.api.botplatform.service.BotTableService;
import app.coincidir.api.botplatform.service.EmailReminderJob;
import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.ConversationLogRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * BotTableAdminController — CRUD desde /admin de las tablas custom del bot
 * y de los registros que contienen.
 *
 *   GET    /api/admin/bot-tables
 *   POST   /api/admin/bot-tables
 *   GET    /api/admin/bot-tables/{id}
 *   PUT    /api/admin/bot-tables/{id}
 *   DELETE /api/admin/bot-tables/{id}     (borra tabla + todos sus registros)
 *
 *   GET    /api/admin/bot-tables/{id}/records   ?page=&size=
 *   POST   /api/admin/bot-tables/{id}/records
 *   PUT    /api/admin/bot-tables/records/{recordId}
 *   DELETE /api/admin/bot-tables/records/{recordId}
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tables")
@RequiredArgsConstructor
public class BotTableAdminController {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final BotTableService service;
    private final BotTableImportExportService ioService;
    private final EmailTemplateRepository emailTemplateRepo;
    private final EmailLogRepository emailLogRepo;
    private final BotTableEmailService emailService;
    private final EmailReminderJob reminderJob;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    // Para el endpoint admin del chat de una reserva (espejo del de panel).
    // Buscamos el conversation_log por session_id que el record almacena.
    private final ConversationLogRepository conversationLogRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────── Tablas ───────

    @GetMapping
    @Transactional(readOnly = true)
    public List<TableDto> list() {
        return tableRepo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TableDto get(@PathVariable Long id) {
        return toDto(tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping
    @Transactional
    public TableDto create(@RequestBody TableSaveRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body vacío");
        if (req.name == null || req.name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name requerido");
        if (req.slug == null || req.slug.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug requerido");
        try { service.validateSlug(req.slug); }
        catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        if (tableRepo.existsBySlug(req.slug))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una tabla con slug '" + req.slug + "'");
        String validatedSchema;
        try { validatedSchema = service.validateSchema(req.columnsJson); }
        catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }

        BotTable t = new BotTable();
        applyTableRequest(t, req);
        t.setColumnsJson(validatedSchema);
        BotTable saved = tableRepo.save(t);

        try {
            auditService.logCreate(
                "bot_table.create",
                "BotTable",
                String.valueOf(saved.getId()),
                saved.getName() + " (" + saved.getSlug() + ")",
                "admin",
                snapshotTableForAudit(saved)
            );
        } catch (Exception ignored) {}

        return toDto(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public TableDto update(@PathVariable Long id, @RequestBody TableSaveRequest req) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Snapshot ANTES de cambios
        Map<String, Object> oldSnap = snapshotTableForAudit(t);

        // Slug no se puede cambiar (rompe referencias en conversaciones existentes)
        if (req.slug != null && !req.slug.equals(t.getSlug()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El slug no se puede cambiar después de crear la tabla");
        if (req.columnsJson != null) {
            try { t.setColumnsJson(service.validateSchema(req.columnsJson)); }
            catch (BotTableService.SchemaError e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        }
        applyTableRequest(t, req);
        BotTable saved = tableRepo.save(t);

        try {
            Map<String, Object> newSnap = snapshotTableForAudit(saved);
            if (!oldSnap.equals(newSnap)) {
                auditService.logUpdate(
                    "bot_table.update",
                    "BotTable",
                    String.valueOf(saved.getId()),
                    saved.getName() + " (" + saved.getSlug() + ")",
                    "admin",
                    oldSnap,
                    newSnap
                );
            }
        } catch (Exception ignored) {}

        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        // Snapshot previo para audit. Si la tabla no existe, ignoramos.
        BotTable t = tableRepo.findById(id).orElse(null);
        Map<String, Object> oldSnap = t != null ? snapshotTableForAudit(t) : Collections.emptyMap();
        String label = t != null ? (t.getName() + " (" + t.getSlug() + ")") : null;

        recordRepo.deleteAllByTableId(id);
        tableRepo.deleteById(id);

        if (t != null) {
            try {
                auditService.logDelete(
                    "bot_table.delete",
                    "BotTable",
                    String.valueOf(id),
                    label,
                    "admin",
                    oldSnap
                );
            } catch (Exception ignored) {}
        }
    }

    // ─────── Registros ───────

    @GetMapping("/{id}/records")
    @Transactional(readOnly = true)
    public RecordsResponse listRecords(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (size <= 0) size = 50;
        if (size > 200) size = 200;
        Page<BotTableRecord> p = recordRepo.findByTableIdOrderByCreatedAtDesc(id, PageRequest.of(page, size));
        RecordsResponse resp = new RecordsResponse();
        resp.items = p.getContent().stream().map(this::toRecordDto).toList();
        resp.total = p.getTotalElements();
        resp.page = page;
        resp.size = size;
        return resp;
    }

    @PostMapping("/{id}/records")
    @Transactional
    public RecordDto createRecord(@PathVariable Long id, @RequestBody RecordSaveRequest req) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            String normalized = service.validateAndNormalizeRecord(t, req.data);
            BotTableRecord rec = new BotTableRecord();
            rec.setTableId(t.getId());
            rec.setDataJson(normalized);
            rec.setSource("admin");
            rec = recordRepo.save(rec);
            // Mismo evento que cuando lo crea el bot — el listener decide si manda mail.
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "created")); } catch (Exception ignored) {}

            try {
                Map<String, Object> snap = jsonToMap(normalized);
                auditService.logCreate(
                    actionKey(t, "create"),
                    entityTypeOf(t),
                    String.valueOf(rec.getId()),
                    buildEntityLabel(t, snap),
                    "admin",
                    snap
                );
            } catch (Exception ignored) {}

            return toRecordDto(rec);
        } catch (BotTableService.SchemaError e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/records/{recordId}")
    @Transactional
    public RecordDto updateRecord(@PathVariable Long recordId, @RequestBody RecordSaveRequest req) {
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BotTable t = tableRepo.findById(rec.getTableId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Snapshot ANTES para audit
        Map<String, Object> auditOldSnap;
        try { auditOldSnap = jsonToMap(rec.getDataJson()); }
        catch (Exception e) { auditOldSnap = Collections.emptyMap(); }

        try {
            // Merge con dataJson actual
            JsonNode current = objectMapper.readTree(rec.getDataJson());
            if (req.data != null && req.data.isObject() && current.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) current).setAll((com.fasterxml.jackson.databind.node.ObjectNode) req.data);
            }
            String normalized = service.validateAndNormalizeRecord(t, current);
            rec.setDataJson(normalized);
            rec = recordRepo.save(rec);
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "updated")); } catch (Exception ignored) {}

            try {
                Map<String, Object> auditNewSnap = jsonToMap(normalized);
                if (!auditOldSnap.equals(auditNewSnap)) {
                    auditService.logUpdate(
                        actionKey(t, "update"),
                        entityTypeOf(t),
                        String.valueOf(rec.getId()),
                        buildEntityLabel(t, auditNewSnap),
                        "admin",
                        auditOldSnap,
                        auditNewSnap
                    );
                }
            } catch (Exception ignored) {}

            return toRecordDto(rec);
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

        // Snapshot previo para audit
        Map<String, Object> auditOldSnap;
        try { auditOldSnap = jsonToMap(rec.getDataJson()); }
        catch (Exception e) { auditOldSnap = Collections.emptyMap(); }
        String auditLabel = t != null ? buildEntityLabel(t, auditOldSnap) : null;

        // Disparar evento ANTES de borrar — el listener necesita poder leer
        // los datos del registro para extraer el email del destinatario.
        if (t != null) {
            try { eventPublisher.publishEvent(new BotTableChangeEvent(t, rec, "cancelled")); }
            catch (Exception ignored) {}
        }
        recordRepo.delete(rec);

        if (t != null) {
            try {
                auditService.logDelete(
                    actionKey(t, "delete"),
                    entityTypeOf(t),
                    String.valueOf(recordId),
                    auditLabel,
                    "admin",
                    auditOldSnap
                );
            } catch (Exception ignored) {}
        }
    }

    /**
     * Devuelve el chat asociado a una reserva (si fue creada por el bot).
     * Espejo del endpoint de panel `/api/panel/bot-tables/{tableId}/records/{recordId}/conversation`,
     * pero accesible desde admin (usa el JWT de /admin en lugar del de /reserve).
     *
     * Se usa desde Smart Tables → click en una reserva → tab "Chat" del modal.
     *
     * Devuelve 404 si:
     *   - el record no existe
     *   - el record no fue creado por el bot (no tiene session_id)
     *   - el conversation_log todavía no se cerró (chat en curso)
     */
    @GetMapping("/records/{recordId}/conversation")
    @Transactional(readOnly = true)
    public ConversationDto getRecordConversation(@PathVariable Long recordId) {
        BotTableRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record no encontrado"));

        String sessionId = rec.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Esta reserva no fue creada por el bot — no hay conversación asociada.");
        }

        Optional<ConversationLog> optLog = conversationLogRepo.findFirstByVisitorIdOrderByIdDesc(sessionId);
        if (optLog.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "La conversación todavía está en curso o aún no fue registrada.");
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
        dto.messagesJson = (log.getMessagesJson() == null || log.getMessagesJson().isBlank())
                ? "[]" : log.getMessagesJson();
        return dto;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConversationDto {
        public String sessionId;
        public java.time.Instant startedAt;
        public java.time.Instant endedAt;
        public Integer messageCount;
        public String closedReason;
        public String deviceType;
        public String deviceOs;
        public String deviceBrowser;
        public String clientFirstName;
        public String clientLastName;
        public String messagesJson;
    }


    // ─────── Import / Export ───────

    /** Descarga la tabla como Excel (.xlsx). */
    @GetMapping("/{id}/export/xlsx")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            byte[] bytes = ioService.exportToXlsx(t);
            String filename = sanitizeFilename(t.getName()) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.warn("[Export xlsx] error", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo exportar: " + e.getMessage());
        }
    }

    /** Descarga la tabla como CSV (UTF-8 con BOM). */
    @GetMapping("/{id}/export/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            byte[] bytes = ioService.exportToCsv(t);
            String filename = sanitizeFilename(t.getName()) + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                    .body(bytes);
        } catch (Exception e) {
            log.warn("[Export csv] error", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo exportar: " + e.getMessage());
        }
    }

    /**
     * Importa registros desde un archivo .xlsx o .csv.
     * Param query 'strict=true|false': si true, abortar si una sola fila falla.
     */
    @PostMapping(path = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BotTableImportExportService.ImportReport importFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strict", defaultValue = "false") boolean strict) {
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                return ioService.importFromXlsx(t, bytes, strict);
            } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
                return ioService.importFromCsv(t, bytes, strict);
            } else {
                // Tratamos de inferir del content-type
                String ct = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
                if (ct.contains("spreadsheet") || ct.contains("excel"))
                    return ioService.importFromXlsx(t, bytes, strict);
                if (ct.contains("csv") || ct.contains("text/plain"))
                    return ioService.importFromCsv(t, bytes, strict);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Formato no soportado. Use .xlsx o .csv");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Import] error", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error procesando archivo: " + e.getMessage());
        }
    }

    private static String sanitizeFilename(String s) {
        if (s == null || s.isBlank()) return "tabla";
        return s.replaceAll("[^A-Za-z0-9 _\\-.áéíóúÁÉÍÓÚñÑ]+", "_").trim();
    }

    // ─────── Email Templates ───────

    private static final List<String> VALID_EVENTS = List.of("created", "updated", "cancelled", "reminder");

    /** Lista todos los templates de una tabla. */
    @GetMapping("/{id}/email-templates")
    @Transactional(readOnly = true)
    public List<EmailTemplateDto> listTemplates(@PathVariable Long id) {
        tableRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return emailTemplateRepo.findByTableIdOrderByEventAsc(id).stream()
                .map(this::toTemplateDto).toList();
    }

    /** Crea o actualiza un template (upsert por table_id + event). */
    @PostMapping("/{id}/email-templates")
    @Transactional
    public EmailTemplateDto saveTemplate(@PathVariable Long id, @RequestBody EmailTemplateRequest req) {
        BotTable t = tableRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body vacío");
        if (req.event == null || !VALID_EVENTS.contains(req.event))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "event inválido. Válidos: " + VALID_EVENTS);
        if (req.subject == null || req.subject.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject requerido");
        if (req.bodyHtml == null || req.bodyHtml.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bodyHtml requerido");
        if (req.replyTo != null && !req.replyTo.isBlank() && !BotTableEmailService.isValidEmail(req.replyTo.trim()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "replyTo no es un email válido");

        EmailTemplate tpl = emailTemplateRepo.findByTableIdAndEvent(t.getId(), req.event)
                .orElseGet(EmailTemplate::new);
        tpl.setTableId(t.getId());
        tpl.setEvent(req.event);
        tpl.setActive(req.active != null ? req.active : true);
        tpl.setSubject(req.subject.trim());
        tpl.setBodyHtml(req.bodyHtml);
        tpl.setReplyTo(req.replyTo != null && !req.replyTo.isBlank() ? req.replyTo.trim() : null);
        tpl.setFromDisplayName(req.fromDisplayName != null && !req.fromDisplayName.isBlank()
                ? req.fromDisplayName.trim() : null);
        return toTemplateDto(emailTemplateRepo.save(tpl));
    }

    @DeleteMapping("/email-templates/{templateId}")
    @Transactional
    public void deleteTemplate(@PathVariable Long templateId) {
        emailTemplateRepo.deleteById(templateId);
    }

    /**
     * Test endpoint: dispara el email para el último registro de la tabla
     * (o uno específico si pasaste recordId). Útil para que el admin pruebe
     * cómo queda el mail real antes de poner el template "en producción".
     */
    @PostMapping("/{id}/email-templates/{templateId}/test")
    @Transactional
    public TestSendResponse testSendTemplate(
            @PathVariable Long id,
            @PathVariable Long templateId,
            @RequestParam(required = false) Long recordId,
            @RequestParam(required = false) String to) {
        BotTable t = tableRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        EmailTemplate tpl = emailTemplateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "template no existe"));
        if (!tpl.getTableId().equals(t.getId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "template no pertenece a esta tabla");

        // Buscar registro: el específico, el último, o ninguno (placeholders quedan vacíos).
        BotTableRecord rec = null;
        if (recordId != null) {
            rec = recordRepo.findById(recordId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "record no existe"));
            if (!rec.getTableId().equals(t.getId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "record no pertenece a esta tabla");
        } else {
            List<BotTableRecord> recs = recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId());
            if (!recs.isEmpty()) rec = recs.get(0);
        }

        TestSendResponse resp = new TestSendResponse();

        if (to != null && !to.isBlank()) {
            // Modo: enviar a casilla custom (no a la del record)
            BotTableEmailService.TestSendResult r = emailService.sendTestToAddress(t, rec, tpl, to);
            resp.attempted = true;
            resp.recordId = rec != null ? rec.getId() : null;
            resp.recipient = r.recipient;
            if (r.ok) {
                resp.message = "Test enviado a " + r.recipient + ". Revisá tu casilla (puede tardar unos segundos).";
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Error al enviar: " + (r.error != null ? r.error : "desconocido"));
            }
        } else {
            // Modo legacy: usar el email de la columna del record (requiere record con email válido)
            if (rec == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La tabla no tiene registros — agregá uno primero o pasá el parámetro 'to' para enviar a una casilla específica.");
            emailService.fireEventSync(t, rec, tpl.getEvent());
            resp.attempted = true;
            resp.recordId = rec.getId();
            resp.message = "Test enviado al email del registro. Revisá la sección Auditoría o tu casilla.";
        }
        return resp;
    }

    /** Lista los logs de email enviados (paginado). */
    @GetMapping("/{id}/email-logs")
    @Transactional(readOnly = true)
    public EmailLogsResponse listEmailLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        if (size <= 0) size = 30;
        if (size > 100) size = 100;
        org.springframework.data.domain.Page<EmailLog> p =
                emailLogRepo.findByTableIdOrderBySentAtDesc(id,
                        org.springframework.data.domain.PageRequest.of(page, size));
        EmailLogsResponse resp = new EmailLogsResponse();
        resp.items = p.getContent().stream().map(this::toLogDto).toList();
        resp.total = p.getTotalElements();
        resp.page = page;
        resp.size = size;
        return resp;
    }

    /**
     * Dispara el job de recordatorios manualmente para una tabla. Útil para
     * testing — el cron sigue corriendo cada 15 min, pero esto te deja
     * verificar al toque sin esperar.
     */
    @PostMapping("/{id}/run-reminders")
    public RunRemindersResponse runReminders(@PathVariable Long id) {
        BotTable t = tableRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        EmailReminderJob.ProcessResult res = reminderJob.processTable(t);
        RunRemindersResponse out = new RunRemindersResponse();
        out.tableSlug = res.tableSlug;
        out.sent = res.sent;
        out.skipped = res.skipped;
        out.errors = res.errors;
        return out;
    }

    private EmailTemplateDto toTemplateDto(EmailTemplate tpl) {
        EmailTemplateDto d = new EmailTemplateDto();
        d.id = tpl.getId();
        d.tableId = tpl.getTableId();
        d.event = tpl.getEvent();
        d.active = tpl.getActive();
        d.subject = tpl.getSubject();
        d.bodyHtml = tpl.getBodyHtml();
        d.replyTo = tpl.getReplyTo();
        d.fromDisplayName = tpl.getFromDisplayName();
        d.updatedAt = tpl.getUpdatedAt();
        return d;
    }

    private EmailLogDto toLogDto(EmailLog l) {
        EmailLogDto d = new EmailLogDto();
        d.id = l.getId();
        d.tableId = l.getTableId();
        d.recordId = l.getRecordId();
        d.event = l.getEvent();
        d.recipient = l.getRecipient();
        d.subject = l.getSubject();
        d.ok = l.getOk();
        d.error = l.getError();
        d.sentAt = l.getSentAt();
        return d;
    }

    // ─────── Mapping ───────

    private void applyTableRequest(BotTable t, TableSaveRequest r) {
        if (r.name != null) t.setName(r.name.trim());
        if (r.slug != null && t.getSlug() == null) t.setSlug(r.slug.trim().toLowerCase());
        if (r.description != null) t.setDescription(r.description);
        if (r.active != null) t.setActive(r.active);
        if (r.confirmAdd != null) t.setConfirmAdd(r.confirmAdd);
        if (r.confirmUpdate != null) t.setConfirmUpdate(r.confirmUpdate);
        if (r.confirmDelete != null) t.setConfirmDelete(r.confirmDelete);
        if (r.injectToPrompt != null) t.setInjectToPrompt(r.injectToPrompt);
        if (r.injectFields != null) t.setInjectFields(r.injectFields.isBlank() ? null : r.injectFields.trim());
        if (r.emailColumn != null) t.setEmailColumn(r.emailColumn.isBlank() ? null : r.emailColumn.trim());
        if (r.phoneColumn != null) t.setPhoneColumn(r.phoneColumn.isBlank() ? null : r.phoneColumn.trim());
        if (r.reminderDateColumn != null) t.setReminderDateColumn(r.reminderDateColumn.isBlank() ? null : r.reminderDateColumn.trim());
        if (r.reminderHoursBefore != null) t.setReminderHoursBefore(r.reminderHoursBefore <= 0 ? null : r.reminderHoursBefore);
        if (r.panelConfigJson != null) t.setPanelConfigJson(r.panelConfigJson.isBlank() ? null : r.panelConfigJson);
    }

    private TableDto toDto(BotTable t) {
        TableDto d = new TableDto();
        d.id = t.getId();
        d.name = t.getName();
        d.slug = t.getSlug();
        d.description = t.getDescription();
        d.columnsJson = t.getColumnsJson();
        d.active = t.getActive();
        d.confirmAdd = t.getConfirmAdd();
        d.confirmUpdate = t.getConfirmUpdate();
        d.confirmDelete = t.getConfirmDelete();
        d.injectToPrompt = t.getInjectToPrompt();
        d.injectFields = t.getInjectFields();
        d.emailColumn = t.getEmailColumn();
        d.phoneColumn = t.getPhoneColumn();
        d.reminderDateColumn = t.getReminderDateColumn();
        d.reminderHoursBefore = t.getReminderHoursBefore();
        d.panelConfigJson = t.getPanelConfigJson();
        d.recordCount = recordRepo.countByTableId(t.getId());
        d.createdAt = t.getCreatedAt();
        d.updatedAt = t.getUpdatedAt();
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
    // Mismo patrón que PanelBotTableController para que ambos generen logs
    // homogéneos. Si en el futuro centralizamos esto, sería un AuditHelpers
    // util compartido.
    // ─────────────────────────────────────────────────────────────────────

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

    private String actionKey(BotTable t, String verb) {
        String slug = t.getSlug() != null ? t.getSlug() : "record";
        if (slug.length() > 2 && slug.endsWith("s")) slug = slug.substring(0, slug.length() - 1);
        return slug + "." + verb;
    }

    private String entityTypeOf(BotTable t) {
        if (t == null) return "Record";
        return t.getName() != null && !t.getName().isBlank() ? t.getName() : "Record";
    }

    private String buildEntityLabel(BotTable t, Map<String, Object> snap) {
        if (snap == null || snap.isEmpty()) return null;
        String name = pickFirstNonBlank(snap,
            "Nombre y Apellido", "Nombre Y Apellido", "nombre", "name",
            "Cliente", "cliente", "Razón Social");
        String date = pickFirstNonBlank(snap,
            "fecha_display", "Fecha Display", "Fecha y Hora Reserva", "fecha", "Fecha");
        if (name != null && date != null) return name + " — " + date;
        if (name != null) return name;
        if (date != null) return date;
        return null;
    }

    private String pickFirstNonBlank(Map<String, Object> snap, String... keys) {
        for (String k : keys) {
            Object v = snap.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return null;
    }

    /**
     * Snapshot de los campos auditables de una BotTable (definición de la
     * tabla en sí — name, slug, columnsJson, config). El columnsJson incluido
     * es CRÍTICO porque cualquier cambio de schema afecta cómo el bot persiste
     * datos.
     */
    private Map<String, Object> snapshotTableForAudit(BotTable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) return m;
        m.put("name", t.getName());
        m.put("slug", t.getSlug());
        m.put("description", t.getDescription());
        m.put("active", t.getActive());
        m.put("columnsJson", t.getColumnsJson());
        m.put("panelConfigJson", t.getPanelConfigJson());
        m.put("confirmAdd", t.getConfirmAdd());
        m.put("confirmUpdate", t.getConfirmUpdate());
        m.put("confirmDelete", t.getConfirmDelete());
        m.put("injectToPrompt", t.getInjectToPrompt());
        m.put("injectFields", t.getInjectFields());
        return m;
    }

    // ─────── DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableSaveRequest {
        public String name;
        public String slug;
        public String description;
        public String columnsJson;
        public Boolean active;
        public Boolean confirmAdd;
        public Boolean confirmUpdate;
        public Boolean confirmDelete;
        public Boolean injectToPrompt;
        public String injectFields;
        public String emailColumn;
        public String phoneColumn;
        public String reminderDateColumn;
        public Integer reminderHoursBefore;
        public String panelConfigJson;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableDto {
        public Long id;
        public String name;
        public String slug;
        public String description;
        public String columnsJson;
        public Boolean active;
        public Boolean confirmAdd;
        public Boolean confirmUpdate;
        public Boolean confirmDelete;
        public Boolean injectToPrompt;
        public String injectFields;
        public String emailColumn;
        public String phoneColumn;
        public String reminderDateColumn;
        public Integer reminderHoursBefore;
        public String panelConfigJson;
        public Long recordCount;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordSaveRequest {
        public JsonNode data;
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
    public static class RecordsResponse {
        public List<RecordDto> items;
        public Long total;
        public Integer page;
        public Integer size;
    }

    // ─────── Email DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailTemplateRequest {
        public String event;
        public Boolean active;
        public String subject;
        public String bodyHtml;
        public String replyTo;
        public String fromDisplayName;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailTemplateDto {
        public Long id;
        public Long tableId;
        public String event;
        public Boolean active;
        public String subject;
        public String bodyHtml;
        public String replyTo;
        public String fromDisplayName;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestSendResponse {
        public Boolean attempted;
        public Long recordId;
        public String recipient;
        public String message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailLogDto {
        public Long id;
        public Long tableId;
        public Long recordId;
        public String event;
        public String recipient;
        public String subject;
        public Boolean ok;
        public String error;
        public Instant sentAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailLogsResponse {
        public List<EmailLogDto> items;
        public Long total;
        public Integer page;
        public Integer size;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RunRemindersResponse {
        public String tableSlug;
        public Integer sent;
        public Integer skipped;
        public Integer errors;
    }
}
