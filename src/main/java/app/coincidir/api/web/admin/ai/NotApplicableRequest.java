package app.coincidir.api.web.admin.ai;

public record NotApplicableRequest(
        String findingTitle,
        String findingDescription,
        String userReason        // opcional, puede ser null
) {}
