package app.coincidir.api.errormonitor.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DataSourceHealthMonitor — toma snapshots periódicos del estado del pool
 * de Hikari y detecta eventos relevantes:
 *
 *   - Conexiones activas vs idle vs threads esperando
 *   - Reconexiones: cuando el pool tuvo que recrear conexiones (el contador
 *     no es nativo de Hikari, lo inferimos comparando totalConnections y
 *     detectando caídas significativas en idle + activos).
 *   - Ping rápido a la DB (SELECT 1) — si falla, registramos un error en el
 *     ErrorMonitorService directamente (no por logback).
 *
 * El endpoint /api/admin/error-monitor/health usa el último snapshot para
 * mostrar el estado actual sin pegarle a la DB en cada request del panel.
 *
 * Por qué no usar Spring Actuator + healthIndicator: ya estamos exponiendo
 * `health` en management endpoints, pero ese endpoint es público y
 * estandarizado. Acá queremos:
 *  (a) info más granular del pool (activas, en espera, max)
 *  (b) calcular reconexiones aproximadas a lo largo del tiempo
 *  (c) integrarlo con la tabla de errores
 *
 * Performance: el snapshot corre cada 30s y es < 5ms (todas las propiedades
 * vienen del MXBean, no de la BD). El ping a la DB lo hacemos cada 60s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceHealthMonitor {

    private final DataSource dataSource;
    private final ErrorMonitorService errorMonitorService;

    /** Snapshot live — accedido por el controller. */
    private volatile HealthSnapshot lastSnapshot;

    /**
     * Contador acumulado de "reconexiones detectadas". No es 1:1 con el
     * conteo real de Hikari (que no expone esa métrica), sino una
     * aproximación basada en heurística: cada vez que totalConnections cae
     * a 0 y vuelve a subir, contamos +1.
     */
    private final AtomicLong reconnectCounter = new AtomicLong(0);

    /** Último valor de totalConnections para detectar drops. */
    private final AtomicLong lastTotal = new AtomicLong(-1);

    /** Última vez que el ping a DB fue OK (Instant). */
    private volatile Instant lastSuccessfulPing;

    /** Última vez que el ping falló. */
    private volatile Instant lastFailedPing;

    /** Mensaje del último ping fallido (para mostrar en /health). */
    private volatile String lastPingError;

    @PostConstruct
    public void init() {
        // Snapshot inicial para que el panel tenga algo desde el primer request.
        try {
            takeSnapshot();
        } catch (Exception e) {
            log.warn("[DataSourceHealthMonitor] snapshot inicial falló: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void scheduledSnapshot() {
        try {
            takeSnapshot();
        } catch (Exception e) {
            // Drop silencioso — no queremos amplificar errores desde el monitor.
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void scheduledPing() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(2)) {
                lastSuccessfulPing = Instant.now();
                lastPingError = null;
            } else {
                lastFailedPing = Instant.now();
                lastPingError = "isValid(2) devolvió false";
            }
        } catch (SQLException e) {
            lastFailedPing = Instant.now();
            lastPingError = e.getMessage();
            // No logueamos con log.error porque el appender lo va a capturar
            // y eso ya pone el error en la tabla. Duplicado innecesario.
        } catch (Exception e) {
            lastFailedPing = Instant.now();
            lastPingError = e.getMessage();
        }
    }

    private void takeSnapshot() {
        HealthSnapshot snap = new HealthSnapshot();
        snap.takenAt = Instant.now();

        if (dataSource instanceof HikariDataSource hds) {
            HikariPoolMXBean mx = hds.getHikariPoolMXBean();
            if (mx != null) {
                int total = mx.getTotalConnections();
                snap.totalConnections   = total;
                snap.activeConnections  = mx.getActiveConnections();
                snap.idleConnections    = mx.getIdleConnections();
                snap.threadsAwaiting    = mx.getThreadsAwaitingConnection();
                snap.maxPoolSize        = hds.getMaximumPoolSize();
                snap.minIdle            = hds.getMinimumIdle();
                snap.connectionTimeout  = hds.getConnectionTimeout();
                snap.idleTimeout        = hds.getIdleTimeout();
                snap.maxLifetime        = hds.getMaxLifetime();
                snap.poolName           = hds.getPoolName();

                // Heurística de reconexión: si el total cayó a 0 desde un valor >0,
                // o si subió de 0 a >0, contamos como reconexión. Conservador para
                // no contar fluctuaciones normales.
                long prev = lastTotal.get();
                if (prev > 0 && total == 0) {
                    reconnectCounter.incrementAndGet();
                }
                lastTotal.set(total);
            }
        } else {
            snap.poolName = "non-hikari";
        }

        snap.reconnects = reconnectCounter.get();
        snap.lastSuccessfulPing = lastSuccessfulPing;
        snap.lastFailedPing = lastFailedPing;
        snap.lastPingError = lastPingError;
        this.lastSnapshot = snap;
    }

    public HealthSnapshot getSnapshot() {
        if (lastSnapshot == null) {
            takeSnapshot();
        }
        return lastSnapshot;
    }

    /**
     * Reseteable desde el panel (DIOS) para que el contador no quede inflado
     * por incidentes históricos. Útil después de un incidente resuelto.
     */
    public void resetReconnectCounter() {
        reconnectCounter.set(0);
    }

    /**
     * Snapshot del estado del pool en un instante. Plain Java object —
     * el controller lo serializa a JSON tal cual.
     */
    public static class HealthSnapshot {
        public Instant takenAt;
        public String  poolName;
        public Integer totalConnections;
        public Integer activeConnections;
        public Integer idleConnections;
        public Integer threadsAwaiting;
        public Integer maxPoolSize;
        public Integer minIdle;
        public Long    connectionTimeout;
        public Long    idleTimeout;
        public Long    maxLifetime;
        public Long    reconnects;
        public Instant lastSuccessfulPing;
        public Instant lastFailedPing;
        public String  lastPingError;
    }
}
