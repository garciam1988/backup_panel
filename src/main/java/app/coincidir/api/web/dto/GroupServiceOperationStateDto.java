package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class GroupServiceOperationStateDto {
    private Long menuItemId;
    private Long groupId;
    private String serviceCode;
    private String displayName;
    private Integer position;

    private String statusCode;
    private String statusLabel;
    private String color;
    private Instant statusUpdatedAt;
}
