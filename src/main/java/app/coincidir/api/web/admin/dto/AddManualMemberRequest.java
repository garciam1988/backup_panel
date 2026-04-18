package app.coincidir.api.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AddManualMemberRequest(
        String firstName,
        String lastName,
        String email,
        String phone,
        String phoneCountryCode,
        String gender,
        String username,
        String password,
        String dni,
        String birthDate,
        String documentType,
        String documentExpiryDate,
        Boolean documentNoExpiry,
        Boolean documentNotApplicable,
        Long countryId,
        String countryName
) {
}
