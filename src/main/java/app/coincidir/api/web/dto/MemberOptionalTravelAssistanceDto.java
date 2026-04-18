package app.coincidir.api.web.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MemberOptionalTravelAssistanceDto {
    private String provider; // requerido
    private String plan;
    private String policyNumber;
    private String emergencyPhone;
    private String notes;
    private BigDecimal cost; // Costo (USD)
    private BigDecimal sale; // Venta (USD)
}
