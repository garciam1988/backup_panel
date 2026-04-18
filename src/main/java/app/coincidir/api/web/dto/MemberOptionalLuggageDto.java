package app.coincidir.api.web.dto;

import app.coincidir.api.domain.OptionalLuggageType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MemberOptionalLuggageDto {
    private OptionalLuggageType type; // requerido
    // Aerolínea seleccionada (nombre). Se usa para precargar y persistir la selección del combo.
    private String airline;
    // Regla seleccionada (tabla airline_baggage_rule). Opcional.
    private Long baggageRuleId;
    private BigDecimal weightKg;
    // Dimensiones asociadas al peso/regla seleccionada.
    private String dimensions;
    private String notes;
    private BigDecimal cost; // Costo (USD)
    private BigDecimal sale; // Venta (USD)
}
