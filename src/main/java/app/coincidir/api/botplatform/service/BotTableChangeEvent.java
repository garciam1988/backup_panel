package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;

/**
 * Evento que se publica cuando ocurre un cambio en una BotTable: created,
 * updated o cancelled. Lo escucha BotTableEmailService para disparar
 * eventualmente un email al destinatario configurado.
 *
 * Usamos eventos de Spring (ApplicationEventPublisher) en vez de inyección
 * directa para mantener desacoplados BotTableService (que es el publisher)
 * y BotTableEmailService (subscriber). Si en el futuro hay otros listeners
 * (notificaciones push, webhooks, etc) se agregan sin tocar el publisher.
 */
public class BotTableChangeEvent {
    public final BotTable table;
    public final BotTableRecord record;
    public final String event; // "created" | "updated" | "cancelled"

    public BotTableChangeEvent(BotTable table, BotTableRecord record, String event) {
        this.table = table;
        this.record = record;
        this.event = event;
    }
}
