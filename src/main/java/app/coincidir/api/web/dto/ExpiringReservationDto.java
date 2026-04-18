package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ExpiringReservationDto {
    private Long groupId;
    private Long menuItemId;
    private String serviceCode;
    private String displayName;
    private String statusCode;
    private String reservationDueDate; // yyyy-MM-dd
    private String destination;
    private String travelDate;
    // Datos específicos para alertas de vencimientos (principalmente alojamientos)
    private String accommodationName;
    private String city;
    private String checkInDate; // yyyy-MM-dd
    private String checkInTime; // HH:mm (o HH:mm:ss)
    // Monto: total costo del servicio
    private BigDecimal totalCost;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
}