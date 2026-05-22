package app.coincidir.api.audit.event;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Evento publicado por cualquier controller/service que ejecutó una acción
 * audit-worthy. Un @EventListener async (AuditEventListener) lo procesa,
 * arma el diff y persiste el AuditLog.
 *
 * El publisher solo necesita armar este evento, no calcular nada — la
 * lógica de diff y el formateo de summary viven en el listener.
 *
 * Para creates: pasar oldValue = null, newValue = mapa con los campos creados.
 * Para deletes: pasar oldValue = mapa con los campos previos, newValue = null.
 * Para updates: ambos mapas, el listener calcula la diferencia.
 * Para acciones sin diff (login, restore backup, activar template): ambos null.
 */
@Data
@Builder
public class AuditEvent {

    /** Acción en formato "entidad.verbo". Ej: "reservation.update". */
    private String action;

    /** Tipo de entidad. Ej: "Reservation". */
    private String entityType;

    /** ID de la entidad afectada. */
    private String entityId;

    /** Etiqueta amigable. Ej: "Reserva de Juan Pérez 17/5 21:00". */
    private String entityLabel;

    /** Módulo. "admin" | "reserve" | "bot" | "system". */
    private String module;

    /** Frase legible. Si es null, el listener arma una genérica desde action. */
    private String summary;

    /** Estado anterior (para updates/deletes). Map<columnName, value>. */
    private Map<String, Object> oldValue;

    /** Estado nuevo (para creates/updates). Map<columnName, value>. */
    private Map<String, Object> newValue;

    /**
     * Si la acción se origina en /reserve o por el bot, source debe ser
     * "panel" o "bot" respectivamente. Si es null, el listener intenta
     * deducirlo del request actual.
     */
    private String source;

    /**
     * Si es true, el listener IGNORA el evento sin persistirlo. Útil para
     * filtros condicionales (ej: cuando una acción la dispara el bot mismo).
     */
    private boolean skip;

    // ── Contexto del request capturado SINCRÓNICAMENTE ──
    //
    // El listener corre en un thread @Async distinto del request, donde el
    // SecurityContextHolder y RequestContextHolder NO están disponibles
    // (son ThreadLocals del thread original). Por eso el AuditService captura
    // estos datos ANTES de publicar el evento y los inyecta acá.
    //
    // Si vienen null, el listener intenta usar SecurityContextHolder como
    // fallback (caso de eventos generados por jobs schedulados).

    /** Username del usuario que disparó la acción (capturado sincrónicamente). */
    private String capturedUsername;

    /** IP del cliente (de X-Forwarded-For o RemoteAddr). */
    private String capturedIp;

    /** User-Agent del request. */
    private String capturedUserAgent;

    /**
     * Sucursal activa del request en el momento de publicar el evento.
     * Capturada del BranchContext.current() porque el listener corre async y
     * ahí el ThreadLocal del filter ya no existe.
     */
    private Long capturedBranchId;
}
