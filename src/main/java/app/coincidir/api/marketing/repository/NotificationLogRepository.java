package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Page<NotificationLog> findByCustomerIdOrderByQueuedAtDesc(Long customerId, Pageable pageable);

    List<NotificationLog> findBySourceTypeAndSourceRefOrderByQueuedAtDesc(
        NotificationLog.SourceType sourceType, String sourceRef);

    Optional<NotificationLog> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    @Modifying
    @Query("DELETE FROM NotificationLog l WHERE l.queuedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
