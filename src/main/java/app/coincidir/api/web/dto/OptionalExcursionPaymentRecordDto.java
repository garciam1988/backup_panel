package app.coincidir.api.web.dto;

import app.coincidir.api.domain.payment.OptionalServicePaymentRecord;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OptionalExcursionPaymentRecordDto {

    private Long id;
    private BigDecimal amountUsd;
    private String paymentMethod;
    private String paymentDate;
    private String receiptLast4;
    private Boolean hasReceipt;
    private String receiptFileName;
    private String receiptContentType;
    private Long bankId;
    private String cardLast4;
    private String createdAt;

    public static OptionalExcursionPaymentRecordDto fromEntity(OptionalServicePaymentRecord r) {
        if (r == null) return null;

        String cn = r.getCardNumber();
        String last4 = null;
        if (cn != null && !cn.isBlank()) {
            String cleaned = cn.trim();
            last4 = cleaned.length() > 4 ? cleaned.substring(cleaned.length() - 4) : cleaned;
        }

        return OptionalExcursionPaymentRecordDto.builder()
                .id(r.getId())
                .amountUsd(r.getAmount())
                .paymentMethod(r.getOneTimeMethod())
                .paymentDate(r.getPaymentDate() != null ? r.getPaymentDate().toString() : null)
                .receiptLast4(r.getReceiptLast4())
                .hasReceipt(r.getReceiptBlob() != null && r.getReceiptBlob().length > 0)
                .receiptFileName(r.getReceiptFileName())
                .receiptContentType(r.getReceiptContentType())
                .bankId(r.getBankId())
                .cardLast4(last4)
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .build();
    }
}
