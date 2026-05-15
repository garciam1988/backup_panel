package app.coincidir.api.web.dto;

import app.coincidir.api.domain.GroupDestinationTransferService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class GroupDestinationTransferServiceDto {

    private Long id;
    private Long groupId;


    private java.math.BigDecimal quotedValue;
    private String quotedAt;

    private java.math.BigDecimal totalCost;
    private String totalCostUpdatedAt;

    private String tripType;

    private String pickupAddress;       // Desde (location)
    private String pickupPointName;     // Nombre específico del punto
    private String dropoffAddress;      // Hasta (location)
    private String dropoffPointName;    // Nombre específico del punto

    private String departureDate;   // yyyy-MM-dd
    private String departureTime;   // HH:mm

    private String returnDate;      // yyyy-MM-dd
    private String returnTime;      // HH:mm

    private String notes;

    private String provider; // Prestador

    private String reservationCode;

    private String country;
    private String city;

    private String departureArrivalTime; // HH:mm
    private String returnArrivalTime;    // HH:mm


    public static GroupDestinationTransferServiceDto fromEntity(GroupDestinationTransferService e) {
        return GroupDestinationTransferServiceDto.builder()
                .id(e.getId())
                .groupId(e.getMenuItem() != null && e.getMenuItem().getGroup() != null ? e.getMenuItem().getGroup().getId() : null)
                .tripType(e.getTripType() != null ? e.getTripType().name() : null)
                .pickupAddress(e.getPickupPlace())
                .pickupPointName(e.getPickupPointName())
                .dropoffAddress(e.getDestinationPlace())
                .dropoffPointName(e.getDestinationPointName())
                .departureDate(e.getDepartureDate() != null ? e.getDepartureDate().toString() : null)
                .departureTime(e.getDepartureTime() != null ? e.getDepartureTime().toString() : null)
                .returnDate(e.getReturnDate() != null ? e.getReturnDate().toString() : null)
                .returnTime(e.getReturnTime() != null ? e.getReturnTime().toString() : null)
                .notes(e.getNotes())
                .provider(e.getProvider())
                .reservationCode(e.getReservationCode())
                .country(e.getCountry())
                .city(e.getCity())
                .departureArrivalTime(e.getDepartureArrivalTime() != null ? e.getDepartureArrivalTime().toString() : null)
                .returnArrivalTime(e.getReturnArrivalTime() != null ? e.getReturnArrivalTime().toString() : null)
                                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .totalCost(e.getTotalCost())
                .totalCostUpdatedAt(e.getTotalCostUpdatedAt() != null ? e.getTotalCostUpdatedAt().toString() : null)
.build();
    }
}
