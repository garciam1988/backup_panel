package app.coincidir.api.web.admin.dto;

import java.math.BigDecimal;

/**
 * Member summary shown inside the admin group list.
 */
public record GroupMemberDto(
        Long id,
        String fullName,
        String email,
        Boolean depositPaid,
        Boolean paymentOk,
        BigDecimal quotedTotal,
        Integer ageMin
) {}
