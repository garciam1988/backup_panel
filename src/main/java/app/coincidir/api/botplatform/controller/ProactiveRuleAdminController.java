package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.ProactiveRule;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.repository.ProactiveRuleFiredRepository;
import app.coincidir.api.botplatform.repository.ProactiveRuleRepository;
import app.coincidir.api.botplatform.service.ProactiveRuleService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ProactiveRuleAdminController — CRUD de reglas proactivas desde el admin.
 *
 * Endpoints:
 *   GET    /api/admin/bot-tables/{tableId}/proactive-rules           → lista reglas de la tabla
 *   POST   /api/admin/bot-tables/{tableId}/proactive-rules           → crea regla
 *   PUT    /api/admin/bot-tables/proactive-rules/{ruleId}            → actualiza regla
 *   DELETE /api/admin/bot-tables/proactive-rules/{ruleId}            → elimina regla
 *   POST   /api/admin/bot-tables/proactive-rules/{ruleId}/test       → prueba la regla on-demand
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tables")
@RequiredArgsConstructor
public class ProactiveRuleAdminController {

    private final ProactiveRuleRepository ruleRepo;
    private final ProactiveRuleFiredRepository firedRepo;
    private final BotTableRepository tableRepo;
    private final ProactiveRuleService ruleService;

    private static final Set<String> VALID_TRIGGER_TYPES = Set.of(
            "last_record", "first_record", "last_user_msg", "fixed_time"
    );

    @GetMapping("/{tableId}/proactive-rules")
    @Transactional(readOnly = true)
    public ProactiveRulesResponse list(@PathVariable Long tableId) {
        if (!tableRepo.existsById(tableId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tabla no encontrada");
        List<ProactiveRule> rules = ruleRepo.findByTableIdOrderByIdAsc(tableId);
        ProactiveRulesResponse resp = new ProactiveRulesResponse();
        resp.items = new ArrayList<>();
        for (ProactiveRule r : rules) resp.items.add(toDto(r));
        return resp;
    }

    @PostMapping("/{tableId}/proactive-rules")
    @Transactional
    public ProactiveRuleDto create(@PathVariable Long tableId, @RequestBody ProactiveRuleSaveRequest req) {
        BotTable t = tableRepo.findById(tableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tabla no encontrada"));
        validateRequest(req);
        ProactiveRule r = new ProactiveRule();
        r.setTableId(t.getId());
        applyRequest(r, req);
        r = ruleRepo.save(r);
        return toDto(r);
    }

    @PutMapping("/proactive-rules/{ruleId}")
    @Transactional
    public ProactiveRuleDto update(@PathVariable Long ruleId, @RequestBody ProactiveRuleSaveRequest req) {
        ProactiveRule r = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Regla no encontrada"));
        validateRequest(req);
        applyRequest(r, req);
        r = ruleRepo.save(r);
        return toDto(r);
    }

    @DeleteMapping("/proactive-rules/{ruleId}")
    @Transactional
    public void delete(@PathVariable Long ruleId) {
        if (!ruleRepo.existsById(ruleId)) return;
        // Borramos también las marcas de "ya disparado" y mensajes pendientes
        // de esta regla quedarían sin owner — se eliminan vía ON DELETE CASCADE
        // (FK), excepto los que ya están en queue (que igual sirven, vamos a
        // dejarlos llegar al cliente y listo).
        try { firedRepo.deleteByRuleId(ruleId); } catch (Exception ignored) {}
        ruleRepo.deleteById(ruleId);
    }

    /** Ejecuta una regla on-demand y reporta cuántos contextos matchean.
     *  No encola mensajes — es solo para preview en el admin. */
    @PostMapping("/proactive-rules/{ruleId}/test")
    @Transactional(readOnly = true)
    public TestRuleResponse test(@PathVariable Long ruleId) {
        ProactiveRuleService.TestRuleResult res = ruleService.testRule(ruleId);
        TestRuleResponse out = new TestRuleResponse();
        out.contextsFound = res.contextsFound;
        out.matchingContexts = res.matchingContexts;
        out.error = res.error;
        return out;
    }

    private void validateRequest(ProactiveRuleSaveRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload requerido");
        if (req.triggerType == null || !VALID_TRIGGER_TYPES.contains(req.triggerType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "triggerType inválido. Válidos: " + VALID_TRIGGER_TYPES);
        if (req.messageTemplate == null || req.messageTemplate.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageTemplate requerido");

        // Validar valores específicos por tipo
        if ("fixed_time".equals(req.triggerType)) {
            if (req.triggerTime == null || !req.triggerTime.matches("^\\d{2}:\\d{2}$"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Para 'fixed_time' triggerTime debe ser 'HH:mm'");
        } else {
            if (req.triggerValue == null || req.triggerValue < 1 || req.triggerValue > 1440)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "triggerValue debe estar entre 1 y 1440 minutos");
        }
    }

    private void applyRequest(ProactiveRule r, ProactiveRuleSaveRequest req) {
        r.setTriggerType(req.triggerType);
        r.setTriggerValue(req.triggerValue);
        r.setTriggerTime(req.triggerTime);
        r.setContextColumn(req.contextColumn);
        r.setMessageTemplate(req.messageTemplate);
        r.setActive(req.active != null ? req.active : true);
        r.setLabel(req.label);
    }

    private ProactiveRuleDto toDto(ProactiveRule r) {
        ProactiveRuleDto d = new ProactiveRuleDto();
        d.id = r.getId();
        d.tableId = r.getTableId();
        d.triggerType = r.getTriggerType();
        d.triggerValue = r.getTriggerValue();
        d.triggerTime = r.getTriggerTime();
        d.contextColumn = r.getContextColumn();
        d.messageTemplate = r.getMessageTemplate();
        d.active = r.getActive();
        d.label = r.getLabel();
        d.updatedAt = r.getUpdatedAt();
        return d;
    }

    // ─────── DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProactiveRulesResponse {
        public List<ProactiveRuleDto> items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProactiveRuleDto {
        public Long id;
        public Long tableId;
        public String triggerType;
        public Integer triggerValue;
        public String triggerTime;
        public String contextColumn;
        public String messageTemplate;
        public Boolean active;
        public String label;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProactiveRuleSaveRequest {
        public String triggerType;
        public Integer triggerValue;
        public String triggerTime;
        public String contextColumn;
        public String messageTemplate;
        public Boolean active;
        public String label;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestRuleResponse {
        public int contextsFound;
        public List<String> matchingContexts;
        public String error;
    }
}
