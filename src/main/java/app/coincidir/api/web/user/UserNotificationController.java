package app.coincidir.api.web.user;

import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.service.UserNotificationService;
import app.coincidir.api.web.user.dto.MarkNotificationReadRequest;
import app.coincidir.api.web.user.dto.MarkAllReadResponse;
import app.coincidir.api.web.user.dto.UnreadCountResponse;
import app.coincidir.api.web.user.dto.UserNotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/user/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService service;

    @GetMapping
    public List<UserNotificationDto> myLatest(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return List.of();
        }
        return service.listMyLatest(principal.getName());
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse myUnreadCount(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return new UnreadCountResponse(0);
        }
        return new UnreadCountResponse(service.unreadCount(principal.getName()));
    }

    @PutMapping("/read-all")
    public MarkAllReadResponse markAllRead(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BadRequestException("No autenticado");
        }
        int updated = service.markAllRead(principal.getName());
        return new MarkAllReadResponse(updated);
    }

    @PutMapping("/{id}/read")
    public UserNotificationDto setRead(
            Principal principal,
            @PathVariable("id") Long id,
            @RequestBody MarkNotificationReadRequest body
    ) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BadRequestException("No autenticado");
        }
        boolean read = body != null && body.read();
        return service.setRead(principal.getName(), id, read);
    }
}
