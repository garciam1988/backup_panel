package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MemberOptionalServiceMenuDto {
    private List<MemberOptionalServiceMenuItemDto> items;
}
