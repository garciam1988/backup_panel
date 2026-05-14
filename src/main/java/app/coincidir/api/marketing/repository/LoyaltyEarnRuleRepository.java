package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyEarnRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoyaltyEarnRuleRepository extends JpaRepository<LoyaltyEarnRule, Long> {

    Page<LoyaltyEarnRule> findAllByProgramIdOrderByPriorityDescIdAsc(Long programId, Pageable pageable);

    List<LoyaltyEarnRule> findAllByProgramIdOrderByPriorityDescIdAsc(Long programId);

    /**
     * Reglas activas en este momento: active=true Y dentro de la ventana
     * validFrom/validUntil. Ordenadas por priority DESC para evaluación.
     */
    @Query("""
        SELECT r FROM LoyaltyEarnRule r
        WHERE r.programId = :programId
          AND r.active = true
          AND (r.validFrom  IS NULL OR r.validFrom  <= :now)
          AND (r.validUntil IS NULL OR r.validUntil >= :now)
        ORDER BY r.priority DESC, r.id ASC
    """)
    List<LoyaltyEarnRule> findActiveAt(@Param("programId") Long programId,
                                      @Param("now") Instant now);
}
