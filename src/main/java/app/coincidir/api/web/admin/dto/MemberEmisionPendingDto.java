package app.coincidir.api.web.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberEmisionPendingDto {
    /** id del registro member_emision */
    private Long emisionId;

    /** id de la TravelRequest creada por "Carga manual de pasajero" */
    private Long requestId;

    private String destination;
    private String travelMonth;
    private String fullName;
    private Integer age;
    private String companionType;
    private String gender;

    private java.math.BigDecimal quotedValue;
    private Instant quotedAt;

    private Instant createdAt;

    /** estado textual (PENDIENTE/EMITIDO/...) */
    private String status;

    private Boolean emitted;
    private Instant emittedAt;
}
