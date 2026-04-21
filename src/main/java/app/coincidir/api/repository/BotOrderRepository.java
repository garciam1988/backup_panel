package app.coincidir.api.repository;

import app.coincidir.api.domain.BotOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BotOrderRepository extends JpaRepository<BotOrder, Long> {

    /** Cuenta pedidos por estado (para los widgets del dashboard). */
    @Query("SELECT o.status, COUNT(o) FROM BotOrder o " +
            "WHERE (:since IS NULL OR o.createdAt >= :since) " +
            "GROUP BY o.status")
    List<Object[]> countByStatus(@Param("since") Instant since);

    /** Suma total de ventas desde una fecha. */
    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM BotOrder o " +
            "WHERE (:since IS NULL OR o.createdAt >= :since) " +
            "AND o.status NOT IN ('CANCELLED')")
    java.math.BigDecimal sumTotal(@Param("since") Instant since);

    /** Búsqueda paginada con filtros. */
    @Query("SELECT o FROM BotOrder o WHERE " +
            "(:status IS NULL OR o.status = :status) AND " +
            "(:brand IS NULL OR LOWER(o.brandName) = LOWER(:brand)) AND " +
            "(:since IS NULL OR o.createdAt >= :since) AND " +
            "(:freeText IS NULL OR " +
            "   LOWER(COALESCE(o.clientName,'')) LIKE LOWER(CONCAT('%', :freeText, '%')) OR " +
            "   LOWER(COALESCE(o.clientPhone,'')) LIKE LOWER(CONCAT('%', :freeText, '%')) OR " +
            "   LOWER(COALESCE(o.orderNumber,'')) LIKE LOWER(CONCAT('%', :freeText, '%'))) " +
            "ORDER BY o.createdAt DESC")
    Page<BotOrder> search(@Param("status") String status,
                          @Param("brand") String brand,
                          @Param("since") Instant since,
                          @Param("freeText") String freeText,
                          Pageable pageable);

    @Query("SELECT MAX(o.id) FROM BotOrder o")
    Long maxId();
}
