package app.coincidir.api.web.user.dto;

import app.coincidir.api.domain.notification.UserNotification;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class UserNotificationDto {
    private Long id;
    private String type;
    private String channel;
    private Long groupId;
    private Long menuItemId;
    private String serviceCode;
    private String serviceLabel;
    private String subject;
    private String message;
    private String linkUrl;
    private Instant createdAt;
    private Instant sentAt;
    private Instant readAt;

    public static UserNotificationDto fromEntity(UserNotification n) {
        if (n == null) return null;
        return UserNotificationDto.builder()
                .id(n.getId())
                .type(n.getType() != null ? n.getType().name() : null)
                .channel(n.getChannel() != null ? n.getChannel().name() : null)
                .groupId(n.getGroupId())
                .menuItemId(n.getMenuItemId())
                .serviceCode(n.getServiceCode())
                .serviceLabel(n.getServiceLabel())
                .subject(n.getSubject())
                .message(n.getMessage())
                .linkUrl(n.getLinkUrl())
                .createdAt(n.getCreatedAt())
                .sentAt(n.getSentAt())
                .readAt(n.getReadAt())
                .build();
    }
}
