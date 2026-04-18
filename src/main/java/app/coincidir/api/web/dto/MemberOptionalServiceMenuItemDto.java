package app.coincidir.api.web.dto;

import app.coincidir.api.domain.MemberOptionalServiceCode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemberOptionalServiceMenuItemDto {
    private Long id;
    private MemberOptionalServiceCode serviceCode;
    private String displayName;
    private Integer position;
}
