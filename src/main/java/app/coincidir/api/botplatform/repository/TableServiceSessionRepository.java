package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.TableServiceSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TableServiceSessionRepository extends JpaRepository<TableServiceSession, Long> {

    /**
     * Sesión activa (ended_at IS NULL) asociada a un record. Por el índice
     * único parcial, solo puede haber UNA. Devuelve Optional.empty() si no
     * hay sesión abierta para ese record.
     */
    @Query("SELECT s FROM TableServiceSession s "
         + "WHERE s.recordId = :recordId AND s.endedAt IS NULL")
    Optional<TableServiceSession> findActiveByRecord(@Param("recordId") Long recordId);

    /**
     * Todas las sesiones activas de una sucursal — lo que pinta el cronómetro
     * de Smart Tables. Orden estable (id asc) para que el frontend reciba
     * resultados deterministas entre polls.
     */
    @Query("SELECT s FROM TableServiceSession s "
         + "WHERE s.branchId = :branchId AND s.endedAt IS NULL "
         + "ORDER BY s.id ASC")
    List<TableServiceSession> findActiveByBranch(@Param("branchId") Long branchId);

    /**
     * Historial filtrado. Cualquier filtro puede ser null para "no filtrar"
     * por esa dimensión. Devuelve paginado, ordenado por startedAt desc
     * (más recientes primero).
     *
     * Solo retorna sesiones CERRADAS (endedAt IS NOT NULL). Si querés ver
     * las activas también, agregá una llamada paralela a findActiveByBranch.
     */
    @Query("SELECT s FROM TableServiceSession s "
         + "WHERE s.branchId = :branchId "
         + "  AND s.endedAt IS NOT NULL "
         + "  AND (:tableLabel IS NULL OR s.tableLabel = :tableLabel) "
         + "  AND (:from IS NULL OR s.startedAt >= :from) "
         + "  AND (:to IS NULL OR s.startedAt < :to) "
         + "ORDER BY s.startedAt DESC")
    List<TableServiceSession> findHistory(@Param("branchId") Long branchId,
                                          @Param("tableLabel") String tableLabel,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to,
                                          Pageable pageable);
}
