package app.coincidir.api.web.dto;

import app.coincidir.api.domain.ServiceDefinition;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ServiceDefinitionDto {
    private Long id;
    private String code;
    private String name;

    public static ServiceDefinitionDto fromEntity(ServiceDefinition e) {
        if (e == null) return null;
        return ServiceDefinitionDto.builder()
                .id(e.getId())
                .code(e.getCode() != null ? e.getCode().name() : null)
                .name(e.getName())
                .build();
    }
}
