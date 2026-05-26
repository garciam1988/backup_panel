package app.coincidir.api.errormonitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Health snapshot agregado: estado del pool de DB + última vez que las
 * APIs externas (Anthropic) funcionaron + uptime del backend.
 *
 * El frontend lo muestra como tira de "semáforos" en la pestaña Resumen
 * para que el operador vea de un vistazo si algo está raro AHORA.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorMonitorHealthDto {

    public Instant generatedAt;
    public long    backendUptimeSeconds;

    // Pool de Hikari
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

    // Reconexiones DB observadas
    public Long    dbReconnectsTotal;
    public Long    dbReconnectsLast24h;
    public Long    dbErrorsLast24h;
    public Long    dbErrorsLast7d;

    // Ping a DB
    public Instant lastSuccessfulPing;
    public Instant lastFailedPing;
    public String  lastPingError;
    /** "OK" | "DEGRADED" | "DOWN" según último ping. */
    public String  dbStatus;

    // APIs externas (calculadas desde tabla de errores)
    public Long    anthropicErrorsLast24h;
    public Long    anthropicErrorsLast7d;
    /** "OK" | "DEGRADED" según haya o no errores recientes. */
    public String  anthropicStatus;
}
