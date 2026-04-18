package app.coincidir.api.web.admin.dto;

public record InstallmentCollectionUpdateRequest(
        Long groupId,
        Long memberId,
        Integer installmentNumber
) {}
