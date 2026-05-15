// app/coincidir/api/web/user/dto/UserGroupMemberDto.java
package app.coincidir.api.web.user.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserGroupMemberDto {
    private Long requestId;
    private String name;
    private String gender;
    private Integer age;
}
