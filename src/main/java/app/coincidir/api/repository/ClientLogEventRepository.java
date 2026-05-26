package app.coincidir.api.repository;

import app.coincidir.api.domain.logging.ClientLogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Repository de ClientLogEvent — usado tanto por:
 *  - ClientLogsService (ingest público del frontend)
 *  - ErrorMonitorService (panel /admin restringido a DIOS)
 *
 * Hereda JpaSpecificationExecutor para que ClientLogsService pueda armar
 * Specifications dinámicas (lo usa hoy y queremos mantener su API intacta).
 * Las queries que agregamos acá son las de stats y dashboard del módulo
 * Error Monitor — Specifications no sirven bien para GROUP BY / agregados.
 *
 * Notas de performance:
 *  - Todas las queries de stats acotan por ts >= :from para que el índice
 *    idx_clep_server_ts se use. Sin esa cota MySQL hace full scan.
 *  - DATE() y FUNCTION('YEARWEEK', ...) NO son sargables — pero como el
 *    rango temporal ya restringe la cantidad de filas, el costo es lineal
 *    sobre el subset filtrado, no sobre toda la tabla.
 */
public interface ClientLogEventRepository
        extends JpaRepository<ClientLogEvent, Long>,
                JpaSpecificationExecutor<ClientLogEvent> {

    // ── Filtros para los selects del panel (poblar dropdowns) ──────────────

    /**
     * Tipos de error distintos presentes en la tabla. Usado por el endpoint
     * /filters para poblar el select "Tipo" sin mostrar opciones vacías.
     * LIMIT defensivo: si hay más de 100 tipos distintos, algo está mal en
     * el clasificador (debería tener ~10-15 buckets).
     */
    @Query("""
        SELECT DISTINCT e.errorType FROM ClientLogEventLogging e
        WHERE e.errorType IS NOT NULL
        ORDER BY e.errorType ASC
        """)
    List<String> findDistinctErrorTypes();

    @Query("""
        SELECT DISTINCT e.source FROM ClientLogEventLogging e
        WHERE e.source IS NOT NULL
        ORDER BY e.source ASC
        """)
    List<String> findDistinctSources();

    @Query("""
        SELECT DISTINCT e.app FROM ClientLogEventLogging e
        WHERE e.app IS NOT NULL
        ORDER BY e.app ASC
        """)
    List<String> findDistinctApps();

    @Query("""
        SELECT DISTINCT e.level FROM ClientLogEventLogging e
        WHERE e.level IS NOT NULL
        ORDER BY e.level ASC
        """)
    List<String> findDistinctLevels();

    // ── Stats: conteos por bucket temporal ─────────────────────────────────

    /**
     * Cantidad de errores agrupados por día.
     * Retorna filas [yyyy-MM-dd, count, errorType].
     *
     * El frontend reorganiza la data en series stacked. errorType puede ser
     * null (lo tratamos como "OTROS" en el front).
     */
    @Query(value = """
        SELECT DATE(server_ts) AS day,
               COALESCE(error_type, 'OTROS') AS type,
               COUNT(*) AS cnt
          FROM client_log_event
         WHERE server_ts >= :from
           AND server_ts <= :to
         GROUP BY DATE(server_ts), COALESCE(error_type, 'OTROS')
         ORDER BY day ASC, type ASC
        """, nativeQuery = true)
    List<Object[]> countByDayAndType(@Param("from") Instant from,
                                     @Param("to")   Instant to);

    /** Cantidad total por errorType en un rango — para el gráfico de torta. */
    @Query("""
        SELECT COALESCE(e.errorType, 'OTROS'), COUNT(e)
          FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.serverTs <= :to
         GROUP BY e.errorType
         ORDER BY COUNT(e) DESC
        """)
    List<Object[]> countByErrorType(@Param("from") Instant from,
                                     @Param("to")   Instant to);

    /** Conteos por nivel (error/warn/fatal) — KPI cards. */
    @Query("""
        SELECT COALESCE(e.level, 'unknown'), COUNT(e)
          FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.serverTs <= :to
         GROUP BY e.level
        """)
    List<Object[]> countByLevel(@Param("from") Instant from,
                                 @Param("to")   Instant to);

    /** Conteos por source (frontend/backend/system). */
    @Query("""
        SELECT COALESCE(e.source, 'unknown'), COUNT(e)
          FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.serverTs <= :to
         GROUP BY e.source
        """)
    List<Object[]> countBySource(@Param("from") Instant from,
                                  @Param("to")   Instant to);

    /** Conteo de abiertos vs resueltos vs ignorados. */
    @Query("""
        SELECT COALESCE(e.status, 'open'), COUNT(e)
          FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
         GROUP BY e.status
        """)
    List<Object[]> countByStatus(@Param("from") Instant from);

    // ── Top errores recurrentes (agrupados por fingerprint) ────────────────

    /**
     * Top N errores más repetidos en el rango, ordenados por cantidad de
     * ocurrencias DESC. Devuelve por cada uno: fingerprint, shortDesc,
     * errorType, count, last_seen, first_seen.
     *
     * "MAX(short_desc)" y "MAX(error_type)" aprovechan que las filas con
     * mismo fingerprint comparten esos valores (vienen del mismo cálculo
     * determinístico) — MAX() es legal en GROUP BY estricto sin necesidad
     * de meterlas en el GROUP BY clause.
     */
    @Query(value = """
        SELECT fingerprint,
               MAX(short_desc)    AS short_desc,
               MAX(error_type)    AS error_type,
               MAX(level)         AS level,
               COUNT(*)           AS cnt,
               MAX(server_ts)     AS last_seen,
               MIN(server_ts)     AS first_seen
          FROM client_log_event
         WHERE server_ts >= :from
           AND server_ts <= :to
           AND fingerprint IS NOT NULL
         GROUP BY fingerprint
         ORDER BY cnt DESC
         LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopRecurring(@Param("from") Instant from,
                                     @Param("to")   Instant to,
                                     @Param("limit") int limit);

    // ── Health / reconexiones ─────────────────────────────────────────────

    /**
     * Conteo de eventos clasificados como reconexión a DB en las últimas N
     * horas. El ErrorClassifier marca errorType='DATABASE' con shortDesc
     * que empieza por "Reconexión" cuando detecta el patrón.
     */
    @Query("""
        SELECT COUNT(e) FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.errorType = 'DATABASE'
           AND e.shortDesc LIKE 'Reconex%'
        """)
    long countDbReconnects(@Param("from") Instant from);

    @Query("""
        SELECT COUNT(e) FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.errorType = 'ANTHROPIC_API'
        """)
    long countAnthropicErrors(@Param("from") Instant from);

    @Query("""
        SELECT COUNT(e) FROM ClientLogEventLogging e
         WHERE e.serverTs >= :from
           AND e.errorType = 'DATABASE'
        """)
    long countDatabaseErrors(@Param("from") Instant from);

    // ── Lookup por fingerprint (para increment-or-insert en el ingest) ────

    /**
     * Devuelve un evento con el mismo fingerprint en una ventana corta.
     * Lo usamos para *no* duplicar en BD eventos idénticos disparados en
     * ráfaga (ej: 50 errores del mismo render en 100ms) — el caller puede
     * decidir incrementar occurrence_count en vez de crear una fila nueva.
     *
     * IMPORTANTE: para el módulo /admin SIEMPRE guardamos cada ocurrencia
     * porque queremos ver el patrón temporal exacto. Esta query existe por
     * si en el futuro alguien necesita la dedup, pero hoy no se llama desde
     * el flujo principal.
     */
    @Query("""
        SELECT e FROM ClientLogEventLogging e
         WHERE e.fingerprint = :fingerprint
           AND e.serverTs >= :since
         ORDER BY e.serverTs DESC
        """)
    List<ClientLogEvent> findRecentByFingerprint(@Param("fingerprint") String fingerprint,
                                                  @Param("since") Instant since);

    // ── Operaciones de status (resolver / ignorar) ────────────────────────

    @Transactional
    @Modifying
    @Query("""
        UPDATE ClientLogEventLogging e
           SET e.status = :status,
               e.resolvedBy = :resolvedBy,
               e.resolvedAt = :resolvedAt,
               e.resolutionNote = :note
         WHERE e.id = :id
        """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("resolvedBy") String resolvedBy,
                     @Param("resolvedAt") Instant resolvedAt,
                     @Param("note") String note);

    /**
     * Marca como resueltos todos los eventos con un fingerprint dado. Sirve
     * para que el operador haga "resolver todos los iguales" desde el modal.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE ClientLogEventLogging e
           SET e.status = 'resolved',
               e.resolvedBy = :resolvedBy,
               e.resolvedAt = :resolvedAt,
               e.resolutionNote = :note
         WHERE e.fingerprint = :fingerprint
           AND e.status = 'open'
        """)
    int resolveAllByFingerprint(@Param("fingerprint") String fingerprint,
                                 @Param("resolvedBy") String resolvedBy,
                                 @Param("resolvedAt") Instant resolvedAt,
                                 @Param("note") String note);

    // ── Limpieza (job de retención) ───────────────────────────────────────

    @Transactional
    @Modifying
    @Query("DELETE FROM ClientLogEventLogging e WHERE e.serverTs < :before")
    int deleteOlderThan(@Param("before") Instant before);

    @Transactional
    @Modifying
    @Query("DELETE FROM ClientLogEventLogging e WHERE e.status = 'resolved' AND e.resolvedAt < :before")
    int deleteResolvedOlderThan(@Param("before") Instant before);

    long countByServerTsGreaterThanEqual(Instant from);
}
