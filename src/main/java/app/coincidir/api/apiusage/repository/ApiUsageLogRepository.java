package app.coincidir.api.apiusage.repository;

import app.coincidir.api.apiusage.domain.ApiUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, Long> {

    /**
     * Devuelve todas las llamadas API de una sesión, ordenadas cronológicamente.
     * Usado por SessionBreakdownService para armar el detalle turno por turno.
     * Spring Data JPA infiere la query del nombre del método — no hace falta
     * @Query explícito.
     */
    List<ApiUsageLog> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** Suma del costo total entre dos fechas (para tarjetas hoy/mes/año). */
    @Query("SELECT COALESCE(SUM(l.costUsd), 0) FROM ApiUsageLog l " +
           "WHERE l.createdAt >= :from AND l.createdAt < :to")
    BigDecimal sumCostBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Conteo de llamadas entre fechas. */
    long countByCreatedAtBetween(Instant from, Instant to);

    /** Suma costo agrupado por provider entre fechas. Devuelve [provider, cost, calls]. */
    @Query("SELECT l.provider, COALESCE(SUM(l.costUsd), 0), COUNT(l) FROM ApiUsageLog l " +
           "WHERE l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.provider ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> sumCostByProvider(@Param("from") Instant from, @Param("to") Instant to);

    /** Suma costo agrupado por feature. */
    @Query("SELECT COALESCE(l.feature, 'unknown'), COALESCE(SUM(l.costUsd), 0), COUNT(l) FROM ApiUsageLog l " +
           "WHERE l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.feature ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> sumCostByFeature(@Param("from") Instant from, @Param("to") Instant to);

    /** Suma costo agrupado por modelo. */
    @Query("SELECT l.provider, COALESCE(l.model, 'unknown'), COALESCE(SUM(l.costUsd), 0), COUNT(l) FROM ApiUsageLog l " +
           "WHERE l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.provider, l.model ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> sumCostByModel(@Param("from") Instant from, @Param("to") Instant to);

    /** Top sesiones por costo. Devuelve [sessionId, totalCost, calls, firstAt, lastAt]. */
    @Query("SELECT l.sessionId, COALESCE(SUM(l.costUsd), 0), COUNT(l), MIN(l.createdAt), MAX(l.createdAt) " +
           "FROM ApiUsageLog l WHERE l.sessionId IS NOT NULL AND l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.sessionId ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> topSessionsByCost(@Param("from") Instant from, @Param("to") Instant to,
                                      org.springframework.data.domain.Pageable pageable);

    /** Suma de tokens (input/output/cacheRead/cacheWrite) en el rango. */
    @Query("SELECT COALESCE(SUM(l.inputTokens), 0), COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.cacheReadTokens), 0), COALESCE(SUM(l.cacheWriteTokens), 0) " +
           "FROM ApiUsageLog l WHERE l.createdAt >= :from AND l.createdAt < :to")
    Object[] sumTokensBetween(@Param("from") Instant from, @Param("to") Instant to);

    // ── Queries específicas para el dashboard del bot ──────────────────
    //
    // Estas queries soportan el endpoint /api/admin/usage/bot-stats.
    // Filtran por feature='chat' (las llamadas del bot conversacional) y
    // excluyen otras llamadas (transcript, extract_client_data, generate_prompt)
    // porque no son representativas del costo "por conversación".

    /**
     * Costo total y conteos del CHAT en el rango.
     *
     * Devuelve [totalCost, totalCalls, totalInput, totalOutput, totalCacheRead, totalCacheWrite].
     * Solo cuenta llamadas con feature='chat' (conversación del bot).
     */
    @Query("SELECT COALESCE(SUM(l.costUsd), 0), COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0), COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.cacheReadTokens), 0), COALESCE(SUM(l.cacheWriteTokens), 0) " +
           "FROM ApiUsageLog l WHERE l.feature = 'chat' " +
           "AND l.createdAt >= :from AND l.createdAt < :to")
    Object[] chatTotals(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Cantidad de sesiones distintas con actividad de chat en el rango.
     * Definimos "sesión" como un sessionId único. Filtra null para excluir
     * llamadas legacy sin trackeo de sesión.
     */
    @Query("SELECT COUNT(DISTINCT l.sessionId) FROM ApiUsageLog l " +
           "WHERE l.feature = 'chat' AND l.sessionId IS NOT NULL " +
           "AND l.createdAt >= :from AND l.createdAt < :to")
    Long countDistinctChatSessions(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Distribución de uso por modelo en sesiones de chat.
     *
     * Devuelve [model, totalCost, calls, sessions] agrupado por modelo.
     * "sessions" cuenta cuántas sesiones distintas usaron ese modelo
     * (una sesión puede usar varios si hubo fallback Haiku→Sonnet).
     */
    @Query("SELECT COALESCE(l.model, 'unknown'), COALESCE(SUM(l.costUsd), 0), " +
           "COUNT(l), COUNT(DISTINCT l.sessionId) " +
           "FROM ApiUsageLog l WHERE l.feature = 'chat' " +
           "AND l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.model ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> chatModelDistribution(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Conteo de turnos por sesión en el rango. Sirve para clasificar
     * conversaciones por complejidad (simple/media/compleja) según el
     * número de turnos.
     *
     * Devuelve filas [sessionId, turnos]. El service las agrupa en buckets.
     */
    @Query("SELECT l.sessionId, COUNT(l) FROM ApiUsageLog l " +
           "WHERE l.feature = 'chat' AND l.sessionId IS NOT NULL " +
           "AND l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.sessionId")
    List<Object[]> turnsPerSession(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Costo diario por día (yyyy-MM-dd) para gráfico de timeline.
     *
     * Devuelve [date, totalCost, totalCalls] agrupado por DAY.
     *
     * NOTA — usamos query nativa SQL:
     * Originalmente esto era JPQL con FUNCTION('DATE', l.createdAt), pero
     * Hibernate 6 tiene comportamiento inconsistente con FUNCTION() para
     * algunas operaciones. Para evitar romper el bootstrap del repo (como
     * pasó con conversationStats), preferimos query nativa SQL desde el
     * principio. Atados a MySQL pero eso ya era el caso de facto.
     */
    @Query(
        value = "SELECT DATE(created_at) AS dia, " +
                "COALESCE(SUM(cost_usd), 0), COUNT(*) " +
                "FROM api_usage_log WHERE feature = 'chat' " +
                "AND created_at >= :from AND created_at < :to " +
                "GROUP BY dia ORDER BY dia ASC",
        nativeQuery = true
    )
    List<Object[]> chatDailyTimeline(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Top sesiones de chat más caras, ordenadas. Reusa el filtro feature='chat'.
     * Devuelve [sessionId, totalCost, calls, firstAt, lastAt].
     */
    @Query("SELECT l.sessionId, COALESCE(SUM(l.costUsd), 0), COUNT(l), " +
           "MIN(l.createdAt), MAX(l.createdAt) " +
           "FROM ApiUsageLog l WHERE l.feature = 'chat' " +
           "AND l.sessionId IS NOT NULL " +
           "AND l.createdAt >= :from AND l.createdAt < :to " +
           "GROUP BY l.sessionId ORDER BY SUM(l.costUsd) DESC")
    List<Object[]> chatTopSessions(@Param("from") Instant from, @Param("to") Instant to,
                                    org.springframework.data.domain.Pageable pageable);
}
