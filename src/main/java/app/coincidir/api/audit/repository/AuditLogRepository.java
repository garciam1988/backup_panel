package app.coincidir.api.audit.repository;

import app.coincidir.api.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Devuelve los logs asociados a una entidad específica (ej: una reserva
     * particular), ordenados de más reciente a más viejo. Se usa para
     * mostrar la trazabilidad inline en el modal de reserva en /reserve.
     *
     * Filtro por entityId solo — el caller puede agregar entityType si
     * necesita mayor precisión cuando varios tipos comparten ID. Para
     * reservas de Brasas no hay conflicto: el ID de bot_table_record es
     * único globalmente.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.entityId = :entityId
          AND (:entityType IS NULL OR a.entityType = :entityType)
        ORDER BY a.ts DESC
        """)
    java.util.List<AuditLog> findByEntity(
        @Param("entityId")   String entityId,
        @Param("entityType") String entityType,
        org.springframework.data.domain.Pageable pageable
    );

    /**
     * Query principal del módulo de auditoría. Filtros opcionales — si llegan
     * null, el WHERE los ignora. Resultados paginados, orden descendente
     * por timestamp.
     *
     * Notas:
     *  - module/action/userId son filtros exactos.
     *  - q (búsqueda libre) hace LIKE sobre summary, entity_label y username
     *    porque son los campos más útiles para encontrar algo "a ojo".
     *  - from/to acotan el rango temporal.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:module     IS NULL OR a.module     = :module)
          AND (:action     IS NULL OR a.action     = :action)
          AND (:userId     IS NULL OR a.userId     = :userId)
          AND (:entityType IS NULL OR a.entityType = :entityType)
          AND (:from       IS NULL OR a.ts >= :from)
          AND (:to         IS NULL OR a.ts <= :to)
          AND (:q IS NULL OR
               LOWER(a.summary)      LIKE LOWER(CONCAT('%', :q, '%')) OR
               LOWER(a.entityLabel)  LIKE LOWER(CONCAT('%', :q, '%')) OR
               LOWER(a.username)     LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY a.ts DESC
        """)
    Page<AuditLog> search(
        @Param("module")     String module,
        @Param("action")     String action,
        @Param("userId")     Long userId,
        @Param("entityType") String entityType,
        @Param("from")       Instant from,
        @Param("to")         Instant to,
        @Param("q")          String q,
        Pageable pageable
    );

    /** Borra logs anteriores a una fecha — usado por el job de retención. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AuditLog a WHERE a.ts < :before AND a.action NOT IN :keepActions")
    int deleteOlderThan(@Param("before") Instant before,
                        @Param("keepActions") java.util.Collection<String> keepActions);

    /** Borra logs muy viejos incluso de acciones críticas (1 año por default). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AuditLog a WHERE a.ts < :before")
    int deleteAllOlderThan(@Param("before") Instant before);
}
