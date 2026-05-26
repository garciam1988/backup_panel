package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Evento que se publica cuando ocurre un cambio en una BotTable: created,
 * updated o cancelled. Lo escuchan:
 *   - {@link BotTableEmailService}: dispara email de confirmación/cambio/cancelación.
 *   - {@link TableServiceTrackerService}: actualiza sesiones de servicio activas.
 *   - {@code BotRecordToLoyaltyListener}: sincroniza loyalty_customer.
 *   - {@link app.coincidir.api.audit.service.BotChangeAuditListener}: genera
 *     entradas en audit_log con username="bot" para que el panel /reserve
 *     pueda mostrar las acciones del bot en el histórico de cada record.
 *
 * Usamos eventos de Spring (ApplicationEventPublisher) en vez de inyección
 * directa para mantener desacoplados BotTableService (publisher) y los
 * subscribers. Si en el futuro hay otros (notificaciones push, webhooks, etc)
 * se agregan sin tocar el publisher.
 *
 * <h3>Compatibilidad con listeners existentes</h3>
 * Los campos {@code oldData} y {@code source} se agregaron para que el
 * audit listener pueda hacer diff y registrar el origen. Son nullable y
 * tienen defaults — los listeners viejos (que solo usan {@code table},
 * {@code record}, {@code event}) siguen funcionando igual sin cambios.
 * Cualquier publisher viejo que use el constructor de 3 args sigue válido.
 */
public class BotTableChangeEvent {
    public final BotTable table;
    public final BotTableRecord record;
    public final String event; // "created" | "updated" | "cancelled"

    /**
     * Snapshot del {@code dataJson} del record ANTES del cambio. Para
     * "created" siempre es null. Para "updated" es el state previo (capturado
     * en {@code BotTableService.doUpdate} antes del save). Para "cancelled"
     * es el state del record que se está eliminando.
     */
    public final JsonNode oldData;

    /**
     * Origen del cambio: "bot" cuando viene del flujo conversacional,
     * "panel" cuando viene de /reserve, "admin" cuando viene de /admin, etc.
     * Default "bot" para el constructor viejo (compat).
     */
    public final String source;

    /**
     * Constructor legacy de 3 args — preservado por compat con publishers
     * que ya estaban antes del módulo de audit. Asume source="bot" y
     * oldData=null. NO usar en código nuevo; usar el de 5 args.
     */
    public BotTableChangeEvent(BotTable table, BotTableRecord record, String event) {
        this(table, record, event, null, "bot");
    }

    public BotTableChangeEvent(BotTable table, BotTableRecord record, String event,
                                JsonNode oldData, String source) {
        this.table = table;
        this.record = record;
        this.event = event;
        this.oldData = oldData;
        this.source = source != null ? source : "bot";
    }
}
