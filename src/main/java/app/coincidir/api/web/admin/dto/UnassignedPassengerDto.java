package app.coincidir.api.web.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnassignedPassengerDto {
    private Long id;
    private String destination;
    private String whenLabel;
    private String datePresetId;
    /**
     * Fecha de viaje (ida) usada para agrupar por preferencia.
     * Se toma de TravelRequestAirService.departureDate cuando exista.
     */
    private LocalDate travelDate;

    /**
     * Fecha de inicio de viaje (solo carga INDIVIDUAL).
     */
    private LocalDate travelStartDate;

    /**
     * True si el registro proviene de carga INDIVIDUAL.
     */
    private Boolean individual;
    private Integer ageMin;
    private Integer ageMax;
    private String name;
    private LocalDate birthDate;
    private String companionPreference;
    private String gender;
}
