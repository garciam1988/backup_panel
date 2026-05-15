package app.coincidir.api.web.user.dto;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTripsMeDto {

    @Builder.Default
    private List<UserTripDto> trips = new ArrayList<>();
}
