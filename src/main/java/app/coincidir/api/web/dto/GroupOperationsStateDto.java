package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class GroupOperationsStateDto {
    private Long groupId;
    private boolean emittedComplete;
    private Instant emittedCompleteAt;

    private boolean servicesComplete;
    private Instant servicesCompleteAt;

    /**
     * Estados actuales por cada item de menú del grupo.
     */
    private List<GroupServiceOperationStateDto> services;

    /**
     * Estados disponibles por tipo de servicio.
     */
    private Map<String, List<ServiceOperationStatusDefinitionDto>> definitionsByService;
}
