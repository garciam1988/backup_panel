package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoyaltyRedemptionRepository extends JpaRepository<LoyaltyRedemption, Long> {

    Optional<LoyaltyRedemption> findByRedemptionCode(String code);

    List<LoyaltyRedemption> findByCustomerIdAndStatusOrderByRequestedAtDesc(Long customerId,
                                                                           LoyaltyRedemption.Status status);

    List<LoyaltyRedemption> findByCustomerIdOrderByRequestedAtDesc(Long customerId);

    /** Canjes PENDING que vencieron (job de expiración). */
    @Query("""
        SELECT r FROM LoyaltyRedemption r
        WHERE r.status = app.coincidir.api.marketing.domain.LoyaltyRedemption.Status.PENDING
          AND r.expiresAt IS NOT NULL
          AND r.expiresAt < :now
    """)
    List<LoyaltyRedemption> findExpiredPending(@Param("now") Instant now);

    int countByCustomerIdAndRewardIdAndStatusIn(Long customerId, Long rewardId,
                                                List<LoyaltyRedemption.Status> statuses);
}
