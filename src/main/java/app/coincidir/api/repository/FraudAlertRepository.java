package app.coincidir.api.repository;

import app.coincidir.api.domain.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    /**
     * Búsqueda con filtros opcionales para la tabla del admin.
     * - brandName (igual exacto case-insensitive)
     * - severity (low/medium/high)
     * - resolved (true/false)
     * - freeText (LIKE sobre reason + suspiciousMessage)
     *
     * Ordena por createdAt DESC.
     */
    @Query("SELECT f FROM FraudAlert f WHERE " +
            "(:brandName IS NULL OR LOWER(f.brandName) = LOWER(:brandName)) AND " +
            "(:severity IS NULL OR f.severity = :severity) AND " +
            "(:resolved IS NULL OR f.resolved = :resolved) AND " +
            "(:freeText IS NULL OR " +
            "   LOWER(COALESCE(f.reason,'')) LIKE LOWER(CONCAT('%', :freeText, '%')) OR " +
            "   LOWER(COALESCE(f.suspiciousMessage,'')) LIKE LOWER(CONCAT('%', :freeText, '%'))) " +
            "ORDER BY f.createdAt DESC")
    Page<FraudAlert> search(@Param("brandName") String brandName,
                            @Param("severity") String severity,
                            @Param("resolved") Boolean resolved,
                            @Param("freeText") String freeText,
                            Pageable pageable);

    long countByResolvedFalse();
}
