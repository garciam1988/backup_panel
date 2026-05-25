package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * TableServiceSession — un turno de mesa "en servicio".
 *
 * Cada vez que una mesa pasa a estado EN SERVICIO se crea una fila acá
 * con {@link #startedAt} = ahora. Cuando la mesa sale del estado EN
 * SERVICIO (TERMINADA, CANCELADA, o vuelve a un estado anterior), se
 * completa {@link #endedAt} + {@link #endedReason}.
 *
 * Mientras {@code endedAt == null} la sesión está activa, y el frontend
 * de Smart Tables usa {@link #startedAt} para mostrar el cronómetro en
 * vivo (HH:MM:SS desde el inicio).
 *
 * Las sesiones cerradas son histórico inmutable — sirven para reportes
 * de duración promedio por turno, mesas que pasan el límite, etc.
 *
 * @see app.coincidir.api.botplatform.service.TableServiceTrackerService
 */
@Entity
@Table(name = "table_service_session")
@Getter @Setter
public class TableServiceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Sucursal a la que pertenece esta sesión. Obligatorio: cada sucursal
     * trackea sus propias mesas independientemente. Las queries siempre
     * filtran por branchId.
     */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    /**
     * Record de la reserva que disparó esta sesión. Puede ser NULL si
     * la sesión fue creada manualmente sin reserva. Hoy en la práctica
     * SIEMPRE es no-null porque el flujo único es vía BotTableChangeEvent.
     *
     * Es el "ancla" para idempotencia: el índice único parcial sobre
     * (record_id, ended_at IS NULL) asegura que no se creen dos sesiones
     * activas para el mismo record.
     */
    @Column(name = "record_id")
    private Long recordId;

    /**
     * BotTable a la que pertenece el record (típicamente la tabla de
     * "Reservas"). Útil para joins / reportes — no es estrictamente
     * necesario porque el recordId ya es único globalmente.
     */
    @Column(name = "bot_table_id")
    private Long botTableId;

    /**
     * ID textual de la mesa al momento de iniciarse el turno (ej "M1",
     * "Barra-6"). NO es FK — Smart Tables guarda las mesas como
     * configuración JSON, no como entidades SQL. Si la mesa cambia de
     * etiqueta después, esta fila preserva el valor original.
     */
    @Column(name = "table_label", nullable = false, length = 64)
    private String tableLabel;

    /**
     * Cantidad de comensales al momento de iniciarse el turno (snapshot
     * tomado del record). Puede ser NULL si la reserva no especifica.
     */
    @Column(name = "persons_count")
    private Integer personsCount;

    /**
     * Nombre del titular de la reserva al iniciarse el turno (snapshot).
     * Útil para reportes — los borrados de records no afectan al
     * histórico de sesiones.
     */
    @Column(name = "title", length = 255)
    private String title;

    /** Timestamp de inicio del turno. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * Timestamp de cierre. NULL mientras la sesión está activa.
     * Junto con endedReason forma el "estado final" de la sesión.
     */
    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * Motivo del cierre. NULL mientras está activa. Valores:
     *   - "completed":    cliente terminó normalmente (TERMINADA / FINALIZADA / PAGADA)
     *   - "cancelled":    reserva cancelada o record borrado
     *   - "reverted":     el operador volvió el status a un estado anterior
     *                     (RESERVADA / PENDIENTE) — caso típico: cambio
     *                     por error y revertido. La sesión queda cerrada
     *                     con duración corta.
     *   - "auto_timeout": reservado para uso futuro. Hoy no se cierra
     *                     automáticamente: la decisión fue dejar las
     *                     sesiones abiertas indefinidamente hasta que la
     *                     cajera cambie el status.
     */
    @Column(name = "ended_reason", length = 20)
    private String endedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (startedAt == null) startedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Helper para vistas. */
    public boolean isActive() {
        return endedAt == null;
    }
}
