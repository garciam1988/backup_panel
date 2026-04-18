package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DynamicDataSourceService — Gestor de pools JDBC por conector.
 *
 * Mantiene un cache (connectorId → HikariDataSource) para no crear un pool
 * nuevo en cada request. Si se edita un conector, hay que llamar a invalidate()
 * para forzar la reconstrucción del pool.
 */
@Slf4j
@Service
public class DynamicDataSourceService {

    private final Map<Long, HikariDataSource> cache = new ConcurrentHashMap<>();

    /**
     * Obtiene (o crea) el DataSource para el conector dado.
     */
    public DataSource getDataSource(BotConnector connector) {
        if (connector.getId() == null) {
            // Caso "probar antes de guardar": construimos el pool al vuelo, sin cache.
            return buildHikari(connector, "test-" + System.nanoTime());
        }
        return cache.computeIfAbsent(connector.getId(), id -> buildHikari(connector, "pool-" + id));
    }

    /** Prueba la conexión abriendo y cerrando un socket. Devuelve null si OK, mensaje de error si falla. */
    public String testConnection(BotConnector connector) {
        HikariDataSource tempDs = null;
        try {
            tempDs = buildHikari(connector, "test-" + System.nanoTime());
            try (Connection conn = tempDs.getConnection()) {
                if (conn.isValid(5)) return null;
                return "Connection inválida (isValid=false)";
            }
        } catch (Exception e) {
            log.warn("Test de conexión falló para {}: {}", connector.getName(), e.getMessage());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (tempDs != null) {
                try { tempDs.close(); } catch (Exception ignore) {}
            }
        }
    }

    /** Invalida el pool cacheado para un conector (usar después de editar). */
    public void invalidate(Long connectorId) {
        HikariDataSource old = cache.remove(connectorId);
        if (old != null) {
            try { old.close(); } catch (Exception e) { log.warn("Error cerrando pool: {}", e.getMessage()); }
        }
    }

    public void invalidateAll() {
        cache.forEach((id, ds) -> {
            try { ds.close(); } catch (Exception ignore) {}
        });
        cache.clear();
    }

    private HikariDataSource buildHikari(BotConnector c, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(c.buildJdbcUrl());
        cfg.setUsername(c.getUsername());
        cfg.setPassword(c.getPassword());
        cfg.setDriverClassName(c.getDriverClassName());
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setConnectionTimeout(10_000); // 10s para no colgar el request
        cfg.setInitializationFailTimeout(-1); // No abortar si la BD no está disponible al arrancar
        return new HikariDataSource(cfg);
    }

    // Cierra todos los pools al apagar la app.
    public void shutdown() {
        invalidateAll();
    }
}
