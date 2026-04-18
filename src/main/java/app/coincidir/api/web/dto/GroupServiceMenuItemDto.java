package app.coincidir.api.web.dto;

import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.operations.OperationStatusCode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class GroupServiceMenuItemDto {
    private Long id;
    private Long groupId;
    private Long serviceId;
    private String serviceCode;
    private String displayName;
    private Integer position;
    private BigDecimal quotedValue;
    private String operationStatus;
    private String notes;

    public static GroupServiceMenuItemDto fromEntity(GroupServiceMenuItem e) {
        if (e == null) return null;
        return GroupServiceMenuItemDto.builder()
                .id(e.getId())
                .groupId(e.getGroup() != null ? e.getGroup().getId() : null)
                .serviceId(e.getService() != null ? e.getService().getId() : null)
                .serviceCode(e.getService() != null && e.getService().getCode() != null ? e.getService().getCode().name() : null)
                .displayName(e.getDisplayName())
                .position(e.getPosition())
                .quotedValue(e.getQuotedValue())
                .operationStatus(e.getOperationStatus() != null ? e.getOperationStatus().name() : null)
                .notes(e.getNotes())
                .build();
    }
}
