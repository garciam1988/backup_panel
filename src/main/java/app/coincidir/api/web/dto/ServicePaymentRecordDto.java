package app.coincidir.api.web.dto;

import app.coincidir.api.domain.payment.ServicePaymentRecord;
import app.coincidir.api.domain.payment.OptionalServicePaymentRecord;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ServicePaymentRecordDto {

    private Long id;
    private Long planId;
    private Long groupId;
    private Long menuItemId;

    private BigDecimal amount;
    private String currency;
    private String paymentDate; // yyyy-MM-dd
    private String totalPaymentCancellationDate; // yyyy-MM-dd (Seña)
    private String oneTimeMethod;
    private String receiptLast4;
    private String receiptNumber;
    private Boolean hasReceipt;
    private String receiptFileName;
    private String receiptContentType;

    private String reservationCode;
    private String flightNumber;

    // AÉREOS
    private Long memberId;
    private String passengerFullName;

    private Long bankId;
    private Long cardId;
    private String cardNumber;

    private Long expenseId;


    public static ServicePaymentRecordDto fromEntity(ServicePaymentRecord r) {
        Long menuItemId = (r.getPlan() != null && r.getPlan().getMenuItem() != null) ? r.getPlan().getMenuItem().getId() : null;
        Long groupId = (r.getPlan() != null && r.getPlan().getMenuItem() != null && r.getPlan().getMenuItem().getGroup() != null)
                ? r.getPlan().getMenuItem().getGroup().getId()
                : null;
        return ServicePaymentRecordDto.builder()
                .id(r.getId())
                .planId(r.getPlan() != null ? r.getPlan().getId() : null)
                .groupId(groupId)
                .menuItemId(menuItemId)
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .paymentDate(r.getPaymentDate() != null ? r.getPaymentDate().toString() : null)
                .totalPaymentCancellationDate(r.getTotalPaymentCancellationDate() != null ? r.getTotalPaymentCancellationDate().toString() : null)
                .oneTimeMethod(r.getOneTimeMethod())
                .receiptLast4(r.getReceiptLast4())
                .receiptNumber(r.getReceiptNumber())
                .hasReceipt(r.getReceiptBlob() != null && r.getReceiptBlob().length > 0)
                .receiptFileName(r.getReceiptFileName())
                .receiptContentType(r.getReceiptContentType())
                .reservationCode(r.getReservationCode())
                .flightNumber(r.getFlightNumber())
                .memberId(r.getMemberId())
                .passengerFullName(r.getPassengerFullName())
                .bankId(r.getBankId())
                .cardId(r.getCardId())
                .cardNumber(r.getCardNumber())
                .expenseId(r.getExpenseId())
                .build();
    }

    
    public static ServicePaymentRecordDto fromEntity(ServicePaymentRecord r, Long expenseId) {
        if (r == null) return null;
        Long menuItemId = (r.getPlan() != null && r.getPlan().getMenuItem() != null) ? r.getPlan().getMenuItem().getId() : null;
        Long groupId = (r.getPlan() != null && r.getPlan().getMenuItem() != null && r.getPlan().getMenuItem().getGroup() != null)
                ? r.getPlan().getMenuItem().getGroup().getId()
                : null;
        return ServicePaymentRecordDto.builder()
                .id(r.getId())
                .planId(r.getPlan() != null ? r.getPlan().getId() : null)
                .groupId(groupId)
                .menuItemId(menuItemId)
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .paymentDate(r.getPaymentDate() != null ? r.getPaymentDate().toString() : null)
                .totalPaymentCancellationDate(r.getTotalPaymentCancellationDate() != null ? r.getTotalPaymentCancellationDate().toString() : null)
                .oneTimeMethod(r.getOneTimeMethod())
                .receiptLast4(r.getReceiptLast4())
                .receiptNumber(r.getReceiptNumber())
                .hasReceipt(r.getReceiptBlob() != null && r.getReceiptBlob().length > 0)
                .receiptFileName(r.getReceiptFileName())
                .receiptContentType(r.getReceiptContentType())
                .reservationCode(r.getReservationCode())
                .flightNumber(r.getFlightNumber())
                .memberId(r.getMemberId())
                .passengerFullName(r.getPassengerFullName())
                .bankId(r.getBankId())
                .cardId(r.getCardId())
                .cardNumber(r.getCardNumber())
                .expenseId(expenseId)
                .build();
    }

public static ServicePaymentRecordDto fromOptionalEntity(OptionalServicePaymentRecord r) {
        Long menuItemId = (r.getPlan() != null && r.getPlan().getMenuItem() != null) ? r.getPlan().getMenuItem().getId() : null;
        Long groupId = (r.getPlan() != null && r.getPlan().getMenuItem() != null
                && r.getPlan().getMenuItem().getMember() != null
                && r.getPlan().getMenuItem().getMember().getGroup() != null)
                ? r.getPlan().getMenuItem().getMember().getGroup().getId()
                : null;

        return ServicePaymentRecordDto.builder()
                .id(r.getId())
                .planId(r.getPlan() != null ? r.getPlan().getId() : null)
                .groupId(groupId)
                .menuItemId(menuItemId)
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .paymentDate(r.getPaymentDate() != null ? r.getPaymentDate().toString() : null)
                .oneTimeMethod(r.getOneTimeMethod())
                .receiptLast4(r.getReceiptLast4())
                .receiptNumber(r.getReceiptNumber())
                .hasReceipt(r.getReceiptBlob() != null && r.getReceiptBlob().length > 0)
                .receiptFileName(r.getReceiptFileName())
                .receiptContentType(r.getReceiptContentType())
                .reservationCode(null)
                .bankId(r.getBankId())
                .cardId(r.getCardId())
                .cardNumber(r.getCardNumber())
                .expenseId(null)
                .build();
    }
}
