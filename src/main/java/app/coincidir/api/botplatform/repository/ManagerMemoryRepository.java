package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ManagerMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ManagerMemoryRepository extends JpaRepository<ManagerMemory, Long> {

    /**
     * Memorias activas (no expiradas, no borradas) ordenadas por antigüedad
     * descendente. Pageable para limitar cuántas inyectamos.
     */
    @Query("SELECT m FROM ManagerMemory m " +
           "WHERE m.kind <> 'deleted' " +
           "  AND (m.expiresAt IS NULL OR m.expiresAt > :now) " +
           "ORDER BY " +
           "  CASE WHEN m.kind = 'permanent' THEN 0 ELSE 1 END ASC, " +
           "  m.createdAt DESC")
    List<ManagerMemory> findActive(@Param("now") Instant now, Pageable pageable);

    /**
     * Memorias permanentes activas (para inyectar siempre, sin importar
     * la cantidad). Las permanentes son pocas y críticas — no las cappeamos.
     */
    @Query("SELECT m FROM ManagerMemory m " +
           "WHERE m.kind = 'permanent' " +
           "  AND m.deletedAt IS NULL " +
           "ORDER BY m.createdAt DESC")
    List<ManagerMemory> findPermanent();

    /**
     * Memorias auto activas (no expiradas, no borradas). Más recientes primero.
     */
    @Query("SELECT m FROM ManagerMemory m " +
           "WHERE m.kind = 'auto' " +
           "  AND m.deletedAt IS NULL " +
           "  AND (m.expiresAt IS NULL OR m.expiresAt > :now) " +
           "ORDER BY m.createdAt DESC")
    List<ManagerMemory> findAutoActive(@Param("now") Instant now, Pageable pageable);

    /**
     * Busca memorias que contengan algún texto (case-insensitive). Útil
     * para el comando "olvidá X" donde X es un substring.
     */
    @Query("SELECT m FROM ManagerMemory m " +
           "WHERE m.deletedAt IS NULL " +
           "  AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<ManagerMemory> searchActive(@Param("query") String query);

    /**
     * Marca como deleted (soft delete) en bulk. Devuelve cantidad de filas.
     */
    @Modifying
    @Query("UPDATE ManagerMemory m " +
           "SET m.kind = 'deleted', m.deletedAt = :now " +
           "WHERE m.id IN :ids")
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /**
     * Limpieza de filas viejas marcadas como deleted (más de 90 días).
     * No se usa hoy automáticamente — queda como housekeeping manual.
     */
    @Modifying
    @Query("DELETE FROM ManagerMemory m " +
           "WHERE m.kind = 'deleted' AND m.deletedAt < :cutoff")
    int hardDeleteOldDeleted(@Param("cutoff") Instant cutoff);
}
