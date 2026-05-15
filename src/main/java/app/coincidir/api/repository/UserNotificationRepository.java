package app.coincidir.api.repository;

import app.coincidir.api.domain.notification.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop100ByRecipientEmailOrderByCreatedAtDesc(String recipientEmail);

    List<UserNotification> findTop100ByRecipientEmailIgnoreCaseOrderByCreatedAtDesc(String recipientEmail);

    long countByRecipientEmailIgnoreCaseAndReadAtIsNull(String recipientEmail);

    @Modifying
    @Query("update UserNotification n set n.readAt = :readAt where lower(n.recipientEmail) = lower(:email) and n.readAt is null")
    int markAllRead(@Param("email") String email, @Param("readAt") Instant readAt);
}
