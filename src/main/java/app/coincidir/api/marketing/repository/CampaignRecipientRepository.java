package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    List<CampaignRecipient> findByCampaignId(Long campaignId);

    Optional<CampaignRecipient> findByCampaignIdAndCustomerId(Long campaignId, Long customerId);

    @Query("""
        SELECT r FROM CampaignRecipient r
        WHERE r.campaignId = :campaignId
          AND (r.whatsappStatus = 'PENDING'
            OR r.emailStatus    = 'PENDING'
            OR r.pushStatus     = 'PENDING')
    """)
    List<CampaignRecipient> findPendingInCampaign(@Param("campaignId") Long campaignId);
}
