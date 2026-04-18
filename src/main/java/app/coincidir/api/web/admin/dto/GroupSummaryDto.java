package app.coincidir.api.web.admin.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

import java.math.BigDecimal;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupSummaryDto {
    private Long id;
    private Long sellerId;
    private String destination;
    private String status;
    private String createdAt;
    private Integer memberCount;
    private List<GroupMemberDto> members;
    private Integer departureYear;

    // resumen calculado a partir de las requests
    private String travelDate;               // "Junio 2025" o "10/06/2025 - 18/06/2025"
    private Map<String, String> commonPrefs; // preferencias en común

    private Boolean autoSearchEnabled;


    private BigDecimal quotedTotal;

    /**
     * Total de venta de la operación = plan.totalAmount del titular (o max entre miembros).
     * En la tabla de miembros se muestra dividido por N miembros.
     * En la tabla principal se muestra este total directo.
     * Null si no hay pagos configurados.
     */
    private BigDecimal totalVenta;

    /**
     * Total a cobrar (Pagos). Para GRUPALES suele ser la suma de los planes de todos los miembros.
     * Para INDIVIDUALES, si existe Titular Pago configurado, se toma el total del titular.
     * Se usa principalmente en Operations Panel para calcular la venta promedio por pasajero.
     */
    private BigDecimal totalToCharge;

    /**
     * True only when ALL member payments are present and verified (conciliated) in Check Panel.
     * Used by Operations Panel to enable/disable service operation actions.
     */
    private Boolean paymentsVerified;
    
    private Boolean operationConfirmed;

    /** True when any member has at least one optional (adicional) service configured. */
    private Boolean hasOptionals;

    /**
     * Operations Panel: resumen de opcionales (ventas en USD) para pintar el botón Adicionales de entrada.
     * Se calcula a nivel grupo para evitar que el frontend tenga que abrir/cerrar el modal para refrescar.
     */
    private BigDecimal optionalsTotalUsd;
    private BigDecimal optionalsTotalExcUsd;
    private BigDecimal optionalsTotalAssistUsd;
    private BigDecimal optionalsTotalEquipUsd;
    private Boolean optionalsAllCompleted;

    /**
     * True when this operación/grupo proviene de carga INDIVIDUAL (travelStartDate presente).
     * Usado por Operations Panel para filtrar Individuales/Grupales.
     */
    private Boolean individual;
// nuevos campos propios del grupo
    private String departureMonth;           // mes de salida elegido en el admin ("Enero", "Febrero", etc.)
    private String travelStartDate;          // fecha inicio del viaje (yyyy-MM-dd)
    private String travelEndDate;            // fecha fin del viaje (yyyy-MM-dd)
}
