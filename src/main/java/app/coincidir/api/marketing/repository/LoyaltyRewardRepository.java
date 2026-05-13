package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoyaltyRewardRepository extends JpaRepository<LoyaltyReward, Long> {

    List<LoyaltyReward> findByProgramIdAndActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(Long programId);

    List<LoyaltyReward> findByProgramIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(Long programId);

    Optional<LoyaltyReward> findByIdAndDeletedAtIsNull(Long id);

    @Query("""
        SELECT r FROM LoyaltyReward r
        WHERE r.programId = :programId
          AND r.active = true
          AND r.deletedAt IS NULL
          AND (r.validFrom  IS NULL OR r.validFrom  <= :now)
          AND (r.validUntil IS NULL OR r.validUntil >= :now)
          AND (r.stockRemaining IS NULL OR r.stockRemaining > 0)
        ORDER BY r.displayOrder ASC, r.id ASC
    """)
    List<LoyaltyReward> findCurrentlyAvailable(@Param("programId") Long programId,
                                               @Param("now") Instant now);
}
