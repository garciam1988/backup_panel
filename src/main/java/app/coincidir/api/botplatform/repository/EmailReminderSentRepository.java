package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.EmailReminderSent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailReminderSentRepository extends JpaRepository<EmailReminderSent, Long> {
    Optional<EmailReminderSent> findByTableIdAndRecordId(Long tableId, Long recordId);

    @Modifying
    @Query("DELETE FROM EmailReminderSent r WHERE r.recordId = :recordId")
    int deleteByRecordId(@Param("recordId") Long recordId);

    @Modifying
    @Query("DELETE FROM EmailReminderSent r WHERE r.tableId = :tableId")
    int deleteByTableId(@Param("tableId") Long tableId);
}
