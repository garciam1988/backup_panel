package app.coincidir.api.service;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.notification.UserNotification;
import app.coincidir.api.repository.UserNotificationRepository;
import app.coincidir.api.web.user.dto.UserNotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserNotificationRepository repo;

    @Transactional(readOnly = true)
    public List<UserNotificationDto> listMyLatest(String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        String normalized = email.trim().toLowerCase();
        return repo.findTop100ByRecipientEmailIgnoreCaseOrderByCreatedAtDesc(normalized)
                .stream()
                .map(UserNotificationDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        if (email == null || email.isBlank()) {
            return 0;
        }
        String normalized = email.trim().toLowerCase();
        return repo.countByRecipientEmailIgnoreCaseAndReadAtIsNull(normalized);
    }

    @Transactional
    public int markAllRead(String email) {
        if (email == null || email.isBlank()) {
            return 0;
        }
        String normalized = email.trim().toLowerCase();
        return repo.markAllRead(normalized, Instant.now());
    }

    @Transactional
    public UserNotificationDto setRead(String email, Long notificationId, boolean read) {
        if (email == null || email.isBlank()) {
            throw new NotFoundException("Notificación no encontrada");
        }
        String normalized = email.trim().toLowerCase();

        UserNotification n = repo.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notificación no encontrada"));

        if (n.getRecipientEmail() == null || !n.getRecipientEmail().equalsIgnoreCase(normalized)) {
            throw new NotFoundException("Notificación no encontrada");
        }

        n.setReadAt(read ? Instant.now() : null);
        repo.save(n);
        return UserNotificationDto.fromEntity(n);
    }
}
