package app.coincidir.api.web.dto;

import app.coincidir.api.domain.FerryTripType;
import app.coincidir.api.domain.GroupFerryService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.format.DateTimeFormatter;

@Getter
@Setter
@Builder
public class GroupFerryServiceDto {

    private Long id;
    private Long groupId;


    private java.math.BigDecimal quotedValue;
    private String quotedAt;

    private java.math.BigDecimal totalCost;
    private String totalCostUpdatedAt;

    private FerryTripType tripType;

    private String originPort;
    private String destinationPort;

    // Strings porque el front los maneja así
    private String departureDate;   // yyyy-MM-dd (ida)
    private String returnDate;      // yyyy-MM-dd (vuelta o null)

    private String ferryCompany;
    private String provider; // Prestador
    private String reservationCode;
    private String departureTime;   // HH:mm (ida)
    private String returnTime;      // HH:mm (vuelta o null)

    private String notes;

    private String departureArrivalTime; // HH:mm
    private String returnArrivalTime;    // HH:mm

    // --- Bus fields ---
    private String busOrigin;
    private String busDestination;
    private String busDepartureTime;
    private String busArrivalTime;
    private String returnBusDepartureTime;
    private String returnBusArrivalTime;


    // ---------- helper para el service ----------

    public static GroupFerryServiceDto fromEntity(GroupFerryService entity) {
        if (entity == null) {
            return null;
        }

        DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

        return GroupFerryServiceDto.builder()
                .id(entity.getId())
                .groupId(
                        entity.getMenuItem() != null && entity.getMenuItem().getGroup() != null
                                ? entity.getMenuItem().getGroup().getId()
                                : null
                )
                .tripType(entity.getTripType())
                .originPort(entity.getOriginPort())
                .destinationPort(entity.getDestinationPort())
                .departureDate(
                        entity.getDepartureDate() != null
                                ? entity.getDepartureDate().format(DATE_FMT)
                                : null
                )
                .returnDate(
                        entity.getReturnDate() != null
                                ? entity.getReturnDate().format(DATE_FMT)
                                : null
                )
                .departureTime(
                        entity.getDepartureTime() != null
                                ? entity.getDepartureTime().format(TIME_FMT)
                                : null
                )
                .returnTime(
                        entity.getReturnTime() != null
                                ? entity.getReturnTime().format(TIME_FMT)
                                : null
                )
                .ferryCompany(entity.getFerryCompany())
                .provider(entity.getProvider())
                .reservationCode(entity.getReservationCode())
                .notes(entity.getNotes())
                .departureArrivalTime(
                        entity.getDepartureArrivalTime() != null
                                ? entity.getDepartureArrivalTime().format(TIME_FMT)
                                : null
                )
                .returnArrivalTime(
                        entity.getReturnArrivalTime() != null
                                ? entity.getReturnArrivalTime().format(TIME_FMT)
                                : null
                )
                .busOrigin(entity.getBusOrigin())
                .busDestination(entity.getBusDestination())
                .busDepartureTime(
                        entity.getBusDepartureTime() != null
                                ? entity.getBusDepartureTime().format(TIME_FMT)
                                : null
                )
                .busArrivalTime(
                        entity.getBusArrivalTime() != null
                                ? entity.getBusArrivalTime().format(TIME_FMT)
                                : null
                )
                .returnBusDepartureTime(
                        entity.getReturnBusDepartureTime() != null
                                ? entity.getReturnBusDepartureTime().format(TIME_FMT)
                                : null
                )
                .returnBusArrivalTime(
                        entity.getReturnBusArrivalTime() != null
                                ? entity.getReturnBusArrivalTime().format(TIME_FMT)
                                : null
                )
                                .quotedValue(entity.getQuotedValue())
                .quotedAt(entity.getQuotedAt() != null ? entity.getQuotedAt().toString() : null)
                .totalCost(entity.getTotalCost())
                .totalCostUpdatedAt(entity.getTotalCostUpdatedAt() != null ? entity.getTotalCostUpdatedAt().toString() : null)
.build();
    }
}
