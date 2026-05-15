package app.coincidir.api.web.dto;

import app.coincidir.api.domain.MemberOptionalServiceCode;
import lombok.Data;

@Data
public class CreateMemberOptionalServiceMenuItemRequest {
    private MemberOptionalServiceCode serviceCode;
}
