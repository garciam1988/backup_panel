// CandidateSummaryDto.java
package app.coincidir.api.web.admin.dto;

import lombok.Builder;

@Builder
public record CandidateSummaryDto(
        Long id,
        String name,
        String email,
        String phone,
        String gender,
        String companionPreference,
        String whenLabel,
        String destination
) {}
