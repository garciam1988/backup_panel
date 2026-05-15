package app.coincidir.api.web.admin.dto;

public record SendVoucherEmailRequest(
        String to,
        String subject,
        String html,
        String attachmentFileName,
        String attachmentContentType,
        String attachmentBase64
) {}
