package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    Page<EmailLog> findAllByOrderBySentAtDesc(Pageable pageable);

    Page<EmailLog> findByTableIdOrderBySentAtDesc(Long tableId, Pageable pageable);

    @Query("SELECT COUNT(l) FROM EmailLog l WHERE l.recipient = :recipient AND l.sentAt > :since AND l.ok = true")
    long countByRecipientSince(@Param("recipient") String recipient, @Param("since") Instant since);

    @Query("SELECT COUNT(l) FROM EmailLog l WHERE l.sentAt > :since AND l.ok = true")
    long countSince(@Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM EmailLog l WHERE l.sentAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
