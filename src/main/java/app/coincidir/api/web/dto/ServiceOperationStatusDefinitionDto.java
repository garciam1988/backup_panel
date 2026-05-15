package app.coincidir.api.web.dto;

import app.coincidir.api.domain.operations.ServiceOperationStatusDefinition;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ServiceOperationStatusDefinitionDto {
    private String serviceCode;
    private String statusCode;
    private String label;
    private String color;
    private Integer sortOrder;

    public static ServiceOperationStatusDefinitionDto fromEntity(ServiceOperationStatusDefinition e) {
        if (e == null) return null;
        return ServiceOperationStatusDefinitionDto.builder()
                .serviceCode(e.getServiceCode() != null ? e.getServiceCode().name() : null)
                .statusCode(e.getStatusCode() != null ? e.getStatusCode().name() : null)
                .label(e.getLabel())
                .color(e.getColor() != null ? e.getColor().name() : null)
                .sortOrder(e.getSortOrder())
                .build();
    }
}
