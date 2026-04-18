package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GroupServiceMenuDto {
    private Long groupId;
    private List<GroupServiceMenuItemDto> items;
}
