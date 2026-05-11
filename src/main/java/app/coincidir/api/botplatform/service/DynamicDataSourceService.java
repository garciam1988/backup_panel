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

    /**
     * Prueba la conexión abriendo y cerrando un socket. Devuelve null si OK,
     * mensaje de error si falla.
     *
     * Usa un timeout más generoso que el del pool runtime (30s vs 10s) porque:
     *   - El test es manual y disparado por un humano que está esperando una
     *     respuesta clara, no es un request del bot que tiene que ser rápido.
     *   - Conectarse a una BD externa la primera vez incluye resolución DNS,
     *     handshake TLS y autenticación, lo que puede tardar más que el
     *     timeout default de Hikari (10s) especialmente cuando ambos puntas
     *     están en Railway y se sale al proxy público (round-trip largo).
     *   - Mantener el runtime en 10s evita colgar requests del bot si la BD
     *     se vuelve lenta o se cae.
     */
    public String testConnection(BotConnector connector) {
        HikariDataSource tempDs = null;
        try {
            tempDs = buildHikari(connector, "test-" + System.nanoTime(), 30_000);
            try (Connection conn = tempDs.getConnection()) {
                if (conn.isValid(5)) return null;
                return "La conexión se abrió pero el driver la reporta como inválida (isValid=false). Verificá que la base de datos esté operativa.";
            }
        } catch (Exception e) {
            log.warn("Test de conexión falló para {}: {}", connector.getName(), e.getMessage());
            return humanizeConnectionError(e);
        } finally {
            if (tempDs != null) {
                try { tempDs.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Traduce las excepciones técnicas de Hikari/JDBC a mensajes accionables
     * en español. El mensaje original de Hikari ("Connection is not available,
     * request timed out after 10000ms") es críptico para alguien que no conoce
     * la lib y no sugiere qué revisar.
     */
    private String humanizeConnectionError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase();

        // Timeout esperando que el pool consiga una conexión — el caso más
        // frecuente cuando hay problemas de red/firewall/host equivocado.
        if (lower.contains("connection is not available") || lower.contains("request timed out")) {
            return "Timeout (no se pudo conectar en 30s). Causas típicas:\n"
                 + "  • Host/puerto incorrectos o la BD no acepta conexiones desde Internet.\n"
                 + "  • Firewall bloqueando el tráfico.\n"
                 + "  • Credenciales vacías o nulas: algunos motores (MySQL) cortan el handshake "
                 + "en silencio cuando reciben usuario sin password, lo que el cliente interpreta "
                 + "como timeout.\n"
                 + "  • Si la BD está en Railway, probá usar el host privado interno "
                 + "(mysql.railway.internal o similar) en lugar del proxy público "
                 + "(*.proxy.rlwy.net) — es más rápido y confiable, pero solo funciona si la BD "
                 + "está en el MISMO proyecto que este backend.\n"
                 + "  • Mensaje original: " + msg;
        }
        // DNS / host no resoluble
        if (lower.contains("unknown host") || lower.contains("name or service not known")
                || lower.contains("nodename nor servname")) {
            return "Host no encontrado. Revisá que el campo Host esté escrito correctamente y sea alcanzable desde donde corre este backend. Mensaje original: " + msg;
        }
        // Conexión rechazada (el host responde pero no hay nadie escuchando en ese puerto)
        if (lower.contains("connection refused")) {
            return "Conexión rechazada por el host (puerto cerrado o servicio caído). Verificá Host y Puerto. Mensaje original: " + msg;
        }
        // Credenciales mal
        if (lower.contains("access denied") || lower.contains("authentication failed")
                || lower.contains("password authentication failed")) {
            return "Credenciales inválidas (usuario o contraseña incorrectos, o el usuario no tiene permiso desde este host). Mensaje original: " + msg;
        }
        // Base de datos inexistente
        if (lower.contains("unknown database") || lower.contains("database \"") && lower.contains("does not exist")) {
            return "La base de datos especificada no existe. Verificá el nombre exacto. Mensaje original: " + msg;
        }
        // SSL / TLS handshake
        if (lower.contains("ssl") || lower.contains("tls")) {
            return "Problema con SSL/TLS durante el handshake. Probá ajustar los parámetros extra (ej: useSSL=false, sslmode=require/disable según el motor). Mensaje original: " + msg;
        }
        // Driver no encontrado
        if (lower.contains("no suitable driver") || lower.contains("classnotfoundexception")) {
            return "No se encontró el driver JDBC para este tipo de BD en el classpath del backend. Mensaje original: " + msg;
        }
        // Fallback: devolver el mensaje original etiquetado con el tipo de excepción
        return e.getClass().getSimpleName() + ": " + msg;
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
        // Runtime default: 10s. Si el bot pide algo de la BD, no queremos
        // esperar 30s si la BD se cayó — el cliente se enojaría.
        return buildHikari(c, poolName, 10_000);
    }

    private HikariDataSource buildHikari(BotConnector c, String poolName, int connectionTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(c.buildJdbcUrl());
        cfg.setUsername(c.getUsername());
        cfg.setPassword(c.getPassword());
        cfg.setDriverClassName(c.getDriverClassName());
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setInitializationFailTimeout(-1); // No abortar si la BD no está disponible al arrancar
        return new HikariDataSource(cfg);
    }

    // Cierra todos los pools al apagar la app.
    public void shutdown() {
        invalidateAll();
    }
}
