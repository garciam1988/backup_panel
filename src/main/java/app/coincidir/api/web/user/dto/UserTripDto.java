package app.coincidir.api.web.user.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTripDto {
    private Long requestId;
    private Long groupId;

    private String destination;
    private String whenLabel;

    // Estado del grupo (OPEN/NEGOTIATION/CLOSED/FINALIZED/...)
    private String status;

    // Estado de la solicitud (NEW/SEARCHING/GROUPED/...)
    private String requestStatus;

    private String travelStartDate; // ISO yyyy-MM-dd
    private String travelEndDate;   // ISO yyyy-MM-dd

    private String createdAt;       // ISO LocalDateTime
}
