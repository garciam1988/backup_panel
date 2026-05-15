package app.coincidir.api.auth.dto;

import java.time.Instant;

public record UserMeDto(
        Long id,
        String email,
        String role,
        String firstName,
        String lastName,
        Instant lastLoginAt
) {}
