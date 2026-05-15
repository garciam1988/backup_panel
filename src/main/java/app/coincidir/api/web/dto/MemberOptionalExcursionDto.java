package app.coincidir.api.web.dto;

import app.coincidir.api.domain.payment.PaymentOneTimeMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MemberOptionalExcursionDto {

    // Nuevo modelo: excursión elegida desde catálogo
    private Long excursionId;

    // Se sigue exponiendo como nombre (para mostrar y compatibilidad)
    private String name; // requerido (si no viene excursionId)

    private String date; // yyyy-MM-dd
    private String time; // HH:mm (salida)

    private String returnTime; // HH:mm (regreso)

    // USD (read-only) - viene del catálogo si excursionId != null
    private BigDecimal cost;

    // USD (editable)
    private BigDecimal sale;

    private PaymentOneTimeMethod paymentMethod;

    // Nuevo: prestador desde tabla
    private Long providerId;

    // Nombre del prestador (para mostrar)
    private String providerName;

    // Compatibilidad: algunos front/records pueden seguir usando provider string
    private String provider;

    private String notes;
}
