package app.coincidir.api.web.dto;

import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.AccommodationRegimen;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import app.coincidir.api.domain.AccommodationContractType;

@Getter
@Setter
@Builder
public class GroupAccommodationServiceDto {

    private Long id;
    private Long groupId;


    private java.math.BigDecimal quotedValue;
    private String quotedAt;

    private java.math.BigDecimal totalCost;
    private String totalCostUpdatedAt;

    private String reservationDueDate;

    private String name;
    private String checkInDate;   // yyyy-MM-dd
    private String checkInTime;   // HH:mm
    private String checkOutDate;  // yyyy-MM-dd
    private String checkOutTime;  // HH:mm

    private AccommodationRegimen regimen;

    private String country;
    private String city;

    private AccommodationContractType contractType;
    private String thirdPartyName;

    // Alias funcional de thirdPartyName (proveedor)
    private String provider;

    private Long providerId;
    private Long accommodationId;

    private String reservationCode;

    private java.math.BigDecimal reservationAmount;

    // Habitaciones (distribución)
    private Integer roomsCount;
    private List<AccommodationRoomDistributionDto> rooms;

    // ------------------------------------------------------------
    // Alias JSON (compatibilidad con front): Proveedor/Prestador
    // ------------------------------------------------------------

    @JsonProperty("proveedor")
    public String getProveedor() {
        return provider;
    }

    @JsonProperty("prestador")
    public String getPrestador() {
        return name;
    }

    @JsonProperty("proveedorId")
    public Long getProveedorId() {
        return providerId;
    }

    @JsonProperty("idProveedor")
    public Long getIdProveedor() {
        return providerId;
    }

    @JsonProperty("prestadorId")
    public Long getPrestadorId() {
        return accommodationId;
    }

    @JsonProperty("idPrestador")
    public Long getIdPrestador() {
        return accommodationId;
    }

    @JsonProperty("alojamientoId")
    public Long getAlojamientoId() {
        return accommodationId;
    }

    public static GroupAccommodationServiceDto fromEntity(GroupAccommodationService e) {
        return GroupAccommodationServiceDto.builder()
                .id(e.getId())
                .groupId(e.getMenuItem() != null && e.getMenuItem().getGroup() != null ? e.getMenuItem().getGroup().getId() : null)
                .name(e.getName())
                .checkInDate(e.getCheckInDate() != null ? e.getCheckInDate().toString() : null)
                .checkInTime(e.getCheckInTime() != null ? e.getCheckInTime().toString() : null)
                .checkOutDate(e.getCheckOutDate() != null ? e.getCheckOutDate().toString() : null)
                .checkOutTime(e.getCheckOutTime() != null ? e.getCheckOutTime().toString() : null)
                .regimen(e.getRegimen())
                .country(e.getCountry())
                .city(e.getCity())
                .contractType(e.getContractType())
                .thirdPartyName(e.getThirdPartyName())
                .provider(e.getThirdPartyName())
                .providerId(e.getProviderId())
                .accommodationId(e.getAccommodationId())
                .reservationCode(e.getReservationCode())
                .reservationAmount(e.getReservationAmount())
                                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .totalCost(e.getTotalCost())
                .totalCostUpdatedAt(e.getTotalCostUpdatedAt() != null ? e.getTotalCostUpdatedAt().toString() : null)
                .reservationDueDate(e.getReservationDueDate() != null ? e.getReservationDueDate().toString() : null)
.build();
    }
}