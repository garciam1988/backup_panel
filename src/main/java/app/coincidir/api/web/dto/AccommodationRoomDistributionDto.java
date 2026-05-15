package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccommodationRoomDistributionDto {

    private Integer roomNumber;
    private Integer adults;
    private Integer minors;

    private String roomType;
}
