package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Borra de un saque todos los recipients de una campaña. Lo usa el delete
     * de campaña para limpiar registros asociados. No hay FK física en la
     * tabla, así que la responsabilidad es del servicio.
     */
    @Modifying
    @Transactional
    long deleteByCampaignId(Long campaignId);
}
