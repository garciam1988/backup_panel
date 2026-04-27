package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.*;
import app.coincidir.api.botplatform.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProactiveRuleService — corazón del sistema de reglas proactivas.
 *
 * Responsabilidades:
 *   - Job programado (@Scheduled) que cada minuto evalúa todas las reglas
 *     activas y encola mensajes en proactive_message_queue cuando matchean.
 *   - Anti-spam: usa proactive_rule_fired para no disparar la misma regla
 *     dos veces para la misma combinación (sesión + contexto).
 *   - Limpieza de marcas cuando un contexto "se cierra" (ej: borraron todos
 *     los items de una mesa) para permitir re-disparo si se reabre.
 *   - Renderizado de placeholders {{columna}} con datos del último record
 *     del contexto.
 *
 * Triggers soportados:
 *   - last_record:   minutos desde el último record agregado en el contexto
 *   - first_record:  minutos desde el primer record del contexto
 *   - last_user_msg: minutos desde el último mensaje del usuario en la sesión
 *                    (placeholder por ahora — requiere tracking de últimos
 *                    mensajes; no implementado hasta tener feature B)
 *   - fixed_time:    todos los días a una hora fija HH:mm
 *
 * Performance: en cada tick recorremos las tablas referenciadas por reglas
 * activas. Para volúmenes esperados (decenas de mesas × decenas de items)
 * es perfectamente factible. Si en el futuro se vuelve costoso, se puede
 * sumar índices o caching.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveRuleService {

    private final ProactiveRuleRepository ruleRepo;
    private final ProactiveRuleFiredRepository firedRepo;
    private final ProactiveMessageQueueRepository queueRepo;
    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_ \\-]+)\\s*\\}\\}");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Job principal — corre cada 60s. Usa fixedDelay (no fixedRate) para que
     * si una corrida tarda más de 60s, la siguiente espere a que termine,
     * evitando solapamiento.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void runProactiveRules() {
        try {
            List<ProactiveRule> active = ruleRepo.findByActiveTrue();
            if (active.isEmpty()) return;

            for (ProactiveRule rule : active) {
                try { processRule(rule); }
                catch (Exception e) {
                    log.warn("[ProactiveRule] error procesando regla {}: {}", rule.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[ProactiveRule] error general en el job: {}", e.getMessage(), e);
        }
    }

    /**
     * Evalúa una regla y dispara mensajes para los contextos que matchean.
     * Devuelve la cantidad de mensajes encolados (útil para el "Probar regla").
     */
    @Transactional
    public int processRule(ProactiveRule rule) {
        BotTable table = tableRepo.findById(rule.getTableId()).orElse(null);
        if (table == null || !Boolean.TRUE.equals(table.getActive())) return 0;

        String type = rule.getTriggerType();
        if (type == null) return 0;

        switch (type) {
            case "last_record":   return processLastRecord(rule, table);
            case "first_record":  return processFirstRecord(rule, table);
            case "fixed_time":    return processFixedTime(rule, table);
            case "last_user_msg":
                // Placeholder — requiere tracking de últimos mensajes del usuario
                // por sesión, que aún no existe (vendría con feature B). Por ahora
                // log y skip.
                log.debug("[ProactiveRule] trigger 'last_user_msg' aún no implementado (regla {})", rule.getId());
                return 0;
            default:
                log.warn("[ProactiveRule] triggerType desconocido '{}' en regla {}", type, rule.getId());
                return 0;
        }
    }

    /** Trigger: minutos desde el ÚLTIMO record agregado en cada contexto. */
    private int processLastRecord(ProactiveRule rule, BotTable table) {
        if (rule.getTriggerValue() == null) return 0;
        int minutes = rule.getTriggerValue();
        Instant threshold = Instant.now().minus(Duration.ofMinutes(minutes));

        Map<String, ContextSnapshot> contexts = groupRecordsByContext(rule, table);
        int fired = 0;
        for (Map.Entry<String, ContextSnapshot> e : contexts.entrySet()) {
            ContextSnapshot ctx = e.getValue();
            if (ctx.lastRecord == null) continue;
            // Si el último record es ANTERIOR al threshold, significa que pasaron
            // ≥ N minutos sin actividad → matchea.
            if (ctx.lastRecord.getCreatedAt().isAfter(threshold)) continue;
            if (ctx.sessionId == null) continue; // sin sessionId no podemos enviar
            if (firedRepo.existsByRuleIdAndSessionIdAndContextKey(
                    rule.getId(), ctx.sessionId, e.getKey())) continue;
            enqueueAndMark(rule, ctx, e.getKey());
            fired++;
        }
        return fired;
    }

    /** Trigger: minutos desde el PRIMER record del contexto. */
    private int processFirstRecord(ProactiveRule rule, BotTable table) {
        if (rule.getTriggerValue() == null) return 0;
        int minutes = rule.getTriggerValue();
        Instant threshold = Instant.now().minus(Duration.ofMinutes(minutes));

        Map<String, ContextSnapshot> contexts = groupRecordsByContext(rule, table);
        int fired = 0;
        for (Map.Entry<String, ContextSnapshot> e : contexts.entrySet()) {
            ContextSnapshot ctx = e.getValue();
            if (ctx.firstRecord == null) continue;
            if (ctx.firstRecord.getCreatedAt().isAfter(threshold)) continue;
            if (ctx.sessionId == null) continue;
            if (firedRepo.existsByRuleIdAndSessionIdAndContextKey(
                    rule.getId(), ctx.sessionId, e.getKey())) continue;
            enqueueAndMark(rule, ctx, e.getKey());
            fired++;
        }
        return fired;
    }

    /**
     * Trigger: a una hora fija HH:mm. Se dispara una vez por día por sesión
     * activa. Como criterio de "sesión activa" usamos las sesiones que tengan
     * al menos un record en la tabla en las últimas 24hs.
     */
    private int processFixedTime(ProactiveRule rule, BotTable table) {
        if (rule.getTriggerTime() == null || rule.getTriggerTime().isBlank()) return 0;
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        LocalTime target;
        try { target = LocalTime.parse(rule.getTriggerTime(), HHMM); }
        catch (Exception ex) { return 0; }
        // Tolerancia: ±1 minuto (porque el job corre cada 60s).
        long diffMin = Math.abs(Duration.between(now, target).toMinutes());
        if (diffMin > 1) return 0;

        Map<String, ContextSnapshot> contexts = groupRecordsByContext(rule, table);
        // ContextKey por día para que se dispare una vez por día por contexto.
        String dayTag = "day:" + java.time.LocalDate.now(ZoneId.systemDefault()).toString();
        int fired = 0;
        for (Map.Entry<String, ContextSnapshot> e : contexts.entrySet()) {
            ContextSnapshot ctx = e.getValue();
            if (ctx.sessionId == null) continue;
            String contextKey = e.getKey() + "|" + dayTag;
            if (firedRepo.existsByRuleIdAndSessionIdAndContextKey(
                    rule.getId(), ctx.sessionId, contextKey)) continue;
            enqueueAndMark(rule, ctx, contextKey);
            fired++;
        }
        return fired;
    }

    /**
     * Agrupa todos los records de la tabla por contextColumn (ej: numero_mesa).
     * Por cada grupo guarda el primer y último record + el sessionId más
     * reciente que haya creado un record (para saber a quién mandar).
     *
     * Si la regla no tiene contextColumn, todos los records caen en el grupo
     * "_global".
     */
    private Map<String, ContextSnapshot> groupRecordsByContext(ProactiveRule rule, BotTable table) {
        Map<String, ContextSnapshot> result = new HashMap<>();
        List<BotTableRecord> records = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());
        String ctxCol = rule.getContextColumn();

        for (BotTableRecord r : records) {
            String contextKey;
            if (ctxCol == null || ctxCol.isBlank()) {
                contextKey = "_global";
            } else {
                String value = extractField(r.getDataJson(), ctxCol);
                if (value == null || value.isBlank()) continue;
                contextKey = value.trim();
            }
            ContextSnapshot snap = result.computeIfAbsent(contextKey, k -> new ContextSnapshot());
            // Como vienen ordenados desc, el primero que vemos es el último (más reciente)
            // y el último que vemos es el primero (más antiguo).
            if (snap.lastRecord == null) {
                snap.lastRecord = r;
                snap.sessionId = r.getSessionId(); // tomamos el del más reciente
            }
            snap.firstRecord = r;
        }
        return result;
    }

    /** Extrae un campo string del data_json. Devuelve null si no existe. */
    private String extractField(String dataJson, String field) {
        if (dataJson == null || field == null) return null;
        try {
            JsonNode node = objectMapper.readTree(dataJson).get(field);
            if (node == null || node.isNull()) return null;
            if (node.isBoolean()) return node.asBoolean() ? "Sí" : "No";
            return node.asText();
        } catch (Exception e) { return null; }
    }

    private void enqueueAndMark(ProactiveRule rule, ContextSnapshot ctx, String contextKey) {
        // Render del mensaje con placeholders del último record
        String message = renderMessage(rule.getMessageTemplate(), ctx.lastRecord);
        // Encolar
        ProactiveMessageQueue m = new ProactiveMessageQueue();
        m.setSessionId(ctx.sessionId);
        m.setRuleId(rule.getId());
        m.setMessage(message);
        queueRepo.save(m);
        // Marcar
        ProactiveRuleFired f = new ProactiveRuleFired();
        f.setRuleId(rule.getId());
        f.setSessionId(ctx.sessionId);
        f.setContextKey(contextKey);
        firedRepo.save(f);
        log.info("[ProactiveRule] regla #{} disparada: session={} ctx={} msg='{}'",
                rule.getId(), ctx.sessionId, contextKey,
                message.length() > 80 ? message.substring(0, 80) + "..." : message);
    }

    /** Renderiza placeholders {{columna}} usando los datos del record. */
    String renderMessage(String template, BotTableRecord record) {
        if (template == null) return "";
        if (record == null) return template;
        JsonNode data;
        try { data = objectMapper.readTree(record.getDataJson()); }
        catch (Exception e) { return template; }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String value;
            JsonNode v = data.get(key);
            if (v == null || v.isNull()) value = "";
            else if (v.isBoolean()) value = v.asBoolean() ? "Sí" : "No";
            else value = v.asText();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Limpia las marcas de "ya disparado" para todas las reglas de una tabla
     * cuando el contexto se libera. Se llama desde BotTableService al borrar
     * un record (ej: cuando se cobra una mesa y se borran sus items).
     *
     * El context_key se calcula con el valor de contextColumn del record que
     * se acaba de borrar/actualizar.
     */
    @Transactional
    public void clearFiredForContext(Long tableId, String contextKey) {
        if (tableId == null || contextKey == null || contextKey.isBlank()) return;
        try {
            int n = firedRepo.deleteByTableIdAndContextKey(tableId, contextKey);
            if (n > 0) log.debug("[ProactiveRule] limpié {} marca(s) para tabla={} ctx={}",
                    n, tableId, contextKey);
        } catch (Exception e) {
            log.warn("[ProactiveRule] error limpiando marcas: {}", e.getMessage());
        }
    }

    /** Para el botón "Probar regla" del admin: ejecuta una regla on-demand y
     *  devuelve cuántos contextos matcharían en este momento (sin encolar). */
    @Transactional(readOnly = true)
    public TestRuleResult testRule(Long ruleId) {
        TestRuleResult res = new TestRuleResult();
        ProactiveRule rule = ruleRepo.findById(ruleId).orElse(null);
        if (rule == null) { res.error = "Regla no encontrada"; return res; }
        BotTable table = tableRepo.findById(rule.getTableId()).orElse(null);
        if (table == null) { res.error = "Tabla no encontrada"; return res; }

        Map<String, ContextSnapshot> contexts = groupRecordsByContext(rule, table);
        res.contextsFound = contexts.size();

        Instant threshold = rule.getTriggerValue() != null
                ? Instant.now().minus(Duration.ofMinutes(rule.getTriggerValue()))
                : null;

        for (Map.Entry<String, ContextSnapshot> e : contexts.entrySet()) {
            ContextSnapshot ctx = e.getValue();
            boolean matches = false;
            switch (rule.getTriggerType()) {
                case "last_record":
                    matches = ctx.lastRecord != null && threshold != null
                            && !ctx.lastRecord.getCreatedAt().isAfter(threshold);
                    break;
                case "first_record":
                    matches = ctx.firstRecord != null && threshold != null
                            && !ctx.firstRecord.getCreatedAt().isAfter(threshold);
                    break;
                case "fixed_time":
                    matches = ctx.sessionId != null;
                    break;
                default: matches = false;
            }
            if (matches) {
                res.matchingContexts.add(e.getKey() + (ctx.sessionId == null ? " (sin sessionId)" : ""));
            }
        }
        return res;
    }

    public static class TestRuleResult {
        public int contextsFound;
        public List<String> matchingContexts = new ArrayList<>();
        public String error;
    }

    private static class ContextSnapshot {
        BotTableRecord firstRecord;
        BotTableRecord lastRecord;
        String sessionId;
    }
}
