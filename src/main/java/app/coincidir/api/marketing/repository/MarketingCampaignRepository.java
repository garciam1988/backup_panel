package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.MarketingCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, Long> {

    Page<MarketingCampaign> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<MarketingCampaign> findByStatus(MarketingCampaign.Status status);

    /** Campañas SCHEDULED cuya hora ya llegó (para el scheduler). */
    @Query("""
        SELECT c FROM MarketingCampaign c
        WHERE c.status = app.coincidir.api.marketing.domain.MarketingCampaign.Status.SCHEDULED
          AND c.scheduledAt IS NOT NULL
          AND c.scheduledAt <= :now
    """)
    List<MarketingCampaign> findDueScheduled(@Param("now") Instant now);
}
