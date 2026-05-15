// app/coincidir/api/web/user/dto/UserGroupInfoDto.java
package app.coincidir.api.web.user.dto;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserGroupInfoDto {
    private Long groupId;
    private String destination;
    private String whenLabel;
    private String status;
    private Integer memberCount;
    private List<UserGroupMemberDto> members;

    public static UserGroupInfoDto empty() {
        return UserGroupInfoDto.builder()
                .groupId(null)
                .destination(null)
                .whenLabel(null)
                .status(null)
                .memberCount(0)
                .members(List.of())
                .build();
    }
}
