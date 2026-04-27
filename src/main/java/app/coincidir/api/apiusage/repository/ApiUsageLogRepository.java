package app.coincidir.api.apiusage.repository;

import app.coincidir.api.apiusage.domain.ApiUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, Long> {

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
}
