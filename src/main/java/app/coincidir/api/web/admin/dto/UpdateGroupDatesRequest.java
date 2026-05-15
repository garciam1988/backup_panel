package app.coincidir.api.web.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateGroupDatesRequest {

    /** Mes de salida elegido manualmente en el panel admin (puede ser null). */
    private String departureMonth;

    private Integer departureYear;

    /** Fecha concreta de inicio del viaje (yyyy-MM-dd). */
    private String travelStartDate;

    /** Fecha concreta de fin del viaje (yyyy-MM-dd). */
    private String travelEndDate;



}
