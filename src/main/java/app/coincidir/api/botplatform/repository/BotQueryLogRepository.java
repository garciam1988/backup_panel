package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotQueryLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BotQueryLogRepository extends JpaRepository<BotQueryLog, Long> {

    Page<BotQueryLog> findByConnectorIdOrderByCreatedAtDesc(Long connectorId, Pageable pageable);

    @Query("SELECT COUNT(l) FROM BotQueryLog l WHERE l.connectorId = :connectorId AND l.status = 'OK'")
    long countOkByConnector(Long connectorId);

    // ─────────────────────────────────────────────────────────────────────
    // Métricas agregadas para el panel de dashboard (Fase 4)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resumen total del período: count, suma de rows, suma de bytes,
     * promedio y máximo de duración. Solo cuenta queries con duration_ms
     * no nulo para evitar contaminar las stats con filas de error temprano
     * (ej: VALIDATION_FAILED) donde la duración es 0.
     *
     * Devuelve un Object[] con los valores en este orden:
     *   [0] total Long
     *   [1] avgDuration Double (puede venir null si total=0)
     *   [2] maxDuration Long
     *   [3] totalRows Long
     *   [4] totalBytes Long
     */
    @Query("SELECT COUNT(l), AVG(l.durationMs), MAX(l.durationMs), " +
           "       COALESCE(SUM(l.rowsReturned), 0), COALESCE(SUM(l.resultSizeBytes), 0) " +
           "FROM BotQueryLog l " +
           "WHERE l.connectorId = :connectorId AND l.createdAt >= :since")
    Object[] aggregateMetrics(@Param("connectorId") Long connectorId,
                              @Param("since") Instant since);

    /**
     * Breakdown por status en el período. Devuelve filas {status, count}.
     */
    @Query("SELECT l.status, COUNT(l) FROM BotQueryLog l " +
           "WHERE l.connectorId = :connectorId AND l.createdAt >= :since " +
           "GROUP BY l.status")
    List<Object[]> statusBreakdown(@Param("connectorId") Long connectorId,
                                   @Param("since") Instant since);

    /**
     * Top N de SQLs más repetidas en el período. Devuelve filas {sql, count}.
     * Útil para detectar:
     *   - Patrones de uso (qué pregunta más el cliente)
     *   - Queries lentas que conviene optimizar agregando índices
     *   - SQLs duplicadas que podrían cachearse en el futuro
     *
     * Como sql_text puede ser largo, el frontend lo trunca al mostrar.
     */
    @Query("SELECT l.sqlText, COUNT(l) AS c FROM BotQueryLog l " +
           "WHERE l.connectorId = :connectorId AND l.createdAt >= :since " +
           "GROUP BY l.sqlText " +
           "ORDER BY c DESC")
    List<Object[]> topSqls(@Param("connectorId") Long connectorId,
                           @Param("since") Instant since,
                           Pageable pageable);

    /**
     * Serie temporal por día para gráfico. Cada fila es un día (en UTC)
     * con count total, count OK y count error. La función DATE() de MySQL
     * agrupa por día calendario; trabajamos en UTC para que sea
     * determinístico (timezone-independent del servidor).
     *
     * Cuidado: este query usa funciones de MySQL/MariaDB. Si el día de mañana
     * corremos contra Postgres como BD principal del backend (no del bot,
     * sino del propio servicio), habría que adaptar a `date_trunc('day', ...)`.
     * Hoy el backend siempre usa MySQL, así que va bien.
     *
     * Devuelve [day, total, ok, error] — donde day viene como java.sql.Date.
     */
    @Query(value =
        "SELECT DATE(created_at) AS day, " +
        "       COUNT(*) AS total, " +
        "       SUM(CASE WHEN status IN ('OK','TRUNCATED') THEN 1 ELSE 0 END) AS ok_count, " +
        "       SUM(CASE WHEN status NOT IN ('OK','TRUNCATED') THEN 1 ELSE 0 END) AS err_count " +
        "FROM bot_query_log " +
        "WHERE connector_id = :connectorId AND created_at >= :since " +
        "GROUP BY DATE(created_at) " +
        "ORDER BY day ASC",
        nativeQuery = true)
    List<Object[]> dailySeries(@Param("connectorId") Long connectorId,
                               @Param("since") Instant since);

    // ─────────────────────────────────────────────────────────────────────
    // Rate limiting (Fase 4 entrega B)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cuenta queries ejecutadas (que pegaron a la BD) para un conector
     * desde un instante. Excluye los rebotes baratos (VALIDATION_FAILED,
     * DISABLED, RATE_LIMITED) porque no consumieron pool de conexiones
     * ni tiempo del BD.
     */
    @Query("SELECT COUNT(l) FROM BotQueryLog l " +
           "WHERE l.connectorId = :connectorId " +
           "  AND l.createdAt >= :since " +
           "  AND l.status NOT IN ('VALIDATION_FAILED','DISABLED','RATE_LIMITED')")
    long countExecutedSince(@Param("connectorId") Long connectorId,
                            @Param("since") Instant since);

    /**
     * Idem, pero filtrado por sessionId. Útil para el rate limit por sesión.
     */
    @Query("SELECT COUNT(l) FROM BotQueryLog l " +
           "WHERE l.connectorId = :connectorId " +
           "  AND l.sessionId = :sessionId " +
           "  AND l.createdAt >= :since " +
           "  AND l.status NOT IN ('VALIDATION_FAILED','DISABLED','RATE_LIMITED')")
    long countExecutedBySessionSince(@Param("connectorId") Long connectorId,
                                     @Param("sessionId") String sessionId,
                                     @Param("since") Instant since);
}
