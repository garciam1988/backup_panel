package app.coincidir.api.audit.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.service.BotTableChangeEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BotChangeAuditListener — convierte eventos {@link BotTableChangeEvent} en
 * entradas del audit log (tabla {@code audit_log}) para que las acciones del
 * bot conversacional aparezcan en el "Histórico" del panel /reserve junto a
 * las del operador humano.
 *
 * <h3>Por qué este listener</h3>
 * Antes el {@link AuditService} filtraba {@code module="bot"} y descartaba
 * el evento. La razón original: "ya queda en conversation_log". Pero en la
 * práctica el operador no abre el chat completo cada vez que quiere ver qué
 * pasó con una reserva — abre /reserve, le da click a la reserva, y quiere
 * un histórico consolidado de TODO lo que pasó: creaciones, modificaciones,
 * cancelaciones, ya sea del operador o del bot.
 *
 * <h3>Cómo funciona</h3>
 * <ul>
 *   <li>Escucha {@link BotTableChangeEvent} con phases "created", "updated",
 *       "cancelled".</li>
 *   <li>Solo procesa eventos con {@code source="bot"} — los cambios del
 *       panel ya generan audit por su propio path (los controllers llaman
 *       directo a {@code auditService.logCreate/logUpdate/logDelete}).</li>
 *   <li>Reusa {@link AuditService} con {@code module="bot"} para que el
 *       AuditEventListener resuelva {@code username="bot"} y
 *       {@code displayName="Bot de WhatsApp"} automáticamente.</li>
 *   <li>Para updates: calcula un diff entre {@code oldData} (snapshot del
 *       record antes del cambio) y los datos actuales. Pasa ambos al audit
 *       para que se vea qué campos cambiaron.</li>
 * </ul>
 *
 * <h3>Async porque no bloqueamos al bot</h3>
 * El bot está respondiendo al usuario en WhatsApp en tiempo real — cualquier
 * delay del audit listener se nota. {@code @Async} ejecuta este código en
 * el thread pool, no en el thread del request.
 *
 * <h3>Best-effort</h3>
 * Si falla el audit, NO se propaga el error al caller. El cambio del record
 * ya se commiteó — el audit es información adicional, no crítica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotChangeAuditListener {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Listener para eventos publicados por {@code BotTableService}. Es
     * {@code @EventListener} normal (no {@code @TransactionalEventListener})
     * a diferencia del email service — acá queremos registrar la intención
     * del cambio aunque la transacción haga rollback, porque ayuda al
     * operador a entender "el bot intentó actualizar X pero tiró error".
     *
     * En la práctica, los cambios del bot rara vez hacen rollback (las
     * transacciones de {@code BotTableService.executeTool} son cortas y
     * autocontenidas), así que la diferencia es minor — pero por simetría
     * con audit del panel preferimos el "intent log".
     */
    @Async
    @EventListener
    public void onBotChange(BotTableChangeEvent ev) {
        try {
            // Filtro defensivo: solo cambios de origen "bot".
            // Si source es panel/admin/import, esos paths ya generan audit
            // por su cuenta y no queremos duplicar.
            if (!"bot".equals(ev.source)) return;

            BotTable table = ev.table;
            BotTableRecord rec = ev.record;
            if (table == null || rec == null) return;

            // Construir la info del audit a partir del nombre y data del record.
            String entityType = entityTypeOf(table);
            String entityId = String.valueOf(rec.getId());

            // Snapshot del data NUEVO (después del cambio) para el diff y la
            // construcción del label. Para "cancelled" lo dejamos null porque
            // el record ya fue borrado físicamente — el label sale del oldData.
            Map<String, Object> newSnap = "cancelled".equals(ev.event)
                    ? null
                    : jsonStringToMap(rec.getDataJson());

            Map<String, Object> oldSnap = ev.oldData != null
                    ? jsonNodeToMap(ev.oldData)
                    : null;

            // Label: priorizamos el snapshot que TIENE info (nuevo si existe,
            // sino viejo). Esto cubre el caso "cancelled" donde solo hay viejo.
            Map<String, Object> labelSource = newSnap != null ? newSnap : oldSnap;
            String entityLabel = buildEntityLabel(labelSource);

            switch (ev.event) {
                case "created":
                    auditService.logCreate(
                            actionKey(table, "create"),
                            entityType,
                            entityId,
                            entityLabel,
                            "bot",
                            newSnap
                    );
                    break;

                case "updated":
                    if (oldSnap == null) {
                        // Sin oldData no podemos hacer diff útil — registramos
                        // igual con summary genérico para que el operador vea
                        // que el bot tocó la reserva.
                        auditService.logActionWithChanges(
                                actionKey(table, "update"),
                                entityType,
                                entityId,
                                entityLabel,
                                "bot",
                                "El bot modificó la reserva (sin diff disponible)",
                                null,
                                newSnap
                        );
                    } else {
                        // Detectar caso especial: cambio de estado (un solo
                        // campo, y ese campo es el statusColumn de la tabla).
                        // Le damos un summary más descriptivo para que en el
                        // histórico se vea "Cambió estado de PENDIENTE a CANCELADA"
                        // en lugar del diff técnico.
                        String statusCol = extractStatusColumn(table);
                        if (statusCol != null && isStatusOnlyChange(oldSnap, newSnap, statusCol)) {
                            String oldStatus = String.valueOf(oldSnap.get(statusCol));
                            String newStatus = String.valueOf(newSnap.get(statusCol));
                            auditService.logActionWithChanges(
                                    actionKey(table, "update"),
                                    entityType,
                                    entityId,
                                    entityLabel,
                                    "bot",
                                    "El bot cambió el estado de " + oldStatus + " a " + newStatus,
                                    oldSnap,
                                    newSnap
                            );
                        } else {
                            // Update normal: el AuditEventListener arma el diff
                            // automáticamente a partir de oldValue/newValue.
                            auditService.logUpdate(
                                    actionKey(table, "update"),
                                    entityType,
                                    entityId,
                                    entityLabel,
                                    "bot",
                                    oldSnap,
                                    newSnap
                            );
                        }
                    }
                    break;

                case "cancelled":
                    // Para cancelaciones del bot usamos delete con el oldSnap
                    // como newValue=null. El AuditEventListener entiende esto
                    // como "borrado" y genera el summary correcto.
                    auditService.logDelete(
                            actionKey(table, "delete"),
                            entityType,
                            entityId,
                            entityLabel,
                            "bot",
                            oldSnap
                    );
                    break;

                default:
                    log.warn("[BotChangeAudit] evento desconocido: {}", ev.event);
            }

        } catch (Exception e) {
            // Best-effort: NUNCA propagar nada al thread que disparó el evento.
            log.warn("[BotChangeAudit] error procesando evento {} de record {}: {}",
                    ev.event,
                    ev.record != null ? ev.record.getId() : null,
                    e.getMessage());
        }
    }

    // ── Helpers (replicados del patrón de PanelBotTableController) ────────

    /**
     * Arma la clave de acción: {slugSingular}.{verbo}.
     * Ej: tabla "reservas" + "update" → "reserva.update". Mantenemos el
     * mismo formato que usa el panel para que el frontend pueda mapear
     * íconos/colores por action sin diferenciar el origen.
     */
    private String actionKey(BotTable t, String verb) {
        String slug = t.getSlug() != null ? t.getSlug() : "record";
        if (slug.length() > 2 && slug.endsWith("s")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug + "." + verb;
    }

    private String entityTypeOf(BotTable t) {
        if (t == null) return "Record";
        return t.getName() != null && !t.getName().isBlank() ? t.getName() : "Record";
    }

    /**
     * Mismo formato que PanelBotTableController.buildEntityLabel: nombre del
     * cliente + fecha. Si no encuentra esos campos, devuelve null y el audit
     * cae al ID como label.
     */
    private String buildEntityLabel(Map<String, Object> snap) {
        if (snap == null || snap.isEmpty()) return null;
        String name = pickFirstNonBlank(snap,
                "Nombre y Apellido", "Nombre Y Apellido", "nombre", "name",
                "Cliente", "cliente", "Razón Social"
        );
        String date = pickFirstNonBlank(snap,
                "fecha_display", "Fecha Display", "Fecha y Hora Reserva", "fecha", "Fecha"
        );
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

    private boolean isStatusOnlyChange(Map<String, Object> oldSnap,
                                       Map<String, Object> newSnap,
                                       String statusCol) {
        if (statusCol == null || oldSnap == null || newSnap == null) return false;
        Set<String> changed = new HashSet<>();
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldSnap.keySet());
        allKeys.addAll(newSnap.keySet());
        for (String k : allKeys) {
            if (!Objects.equals(oldSnap.get(k), newSnap.get(k))) changed.add(k);
        }
        return changed.size() == 1 && changed.contains(statusCol);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonStringToMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            JsonNode n = objectMapper.readTree(json);
            if (n == null || !n.isObject()) return Collections.emptyMap();
            return objectMapper.convertValue(n, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) return Collections.emptyMap();
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
