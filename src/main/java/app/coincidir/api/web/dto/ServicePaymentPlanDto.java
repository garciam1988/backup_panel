package app.coincidir.api.web.dto;

import app.coincidir.api.domain.payment.ServicePaymentPlan;
import app.coincidir.api.domain.payment.OptionalServicePaymentPlan;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class ServicePaymentPlanDto {

    private Long id;
    private Long groupId;
    private Long menuItemId;

    private String paymentForm;
    private BigDecimal totalAmount;
    private String currency;
    private List<ServicePaymentRecordDto> records;
    private List<ExpensePaymentRecordDto> expenseRecords;

    public static ServicePaymentPlanDto fromEntity(ServicePaymentPlan p, List<ServicePaymentRecordDto> records) {
        return fromEntity(p, records, null);
    }

    public static ServicePaymentPlanDto fromEntity(ServicePaymentPlan p, List<ServicePaymentRecordDto> records, List<ExpensePaymentRecordDto> expenseRecords) {
        Long menuItemId = (p.getMenuItem() != null) ? p.getMenuItem().getId() : null;
        Long groupId = (p.getMenuItem() != null && p.getMenuItem().getGroup() != null) ? p.getMenuItem().getGroup().getId() : null;

        return ServicePaymentPlanDto.builder()
                .id(p.getId())
                .groupId(groupId)
                .menuItemId(menuItemId)
                .paymentForm(p.getPaymentForm() != null ? p.getPaymentForm().name() : null)
                .totalAmount(p.getTotalAmount())
                .currency(p.getCurrency())
                .records(records)
                .expenseRecords(expenseRecords)
                .build();
    }

    public static ServicePaymentPlanDto fromOptionalEntity(OptionalServicePaymentPlan p, List<ServicePaymentRecordDto> records) {
        Long menuItemId = (p.getMenuItem() != null) ? p.getMenuItem().getId() : null;
        Long groupId = (p.getMenuItem() != null && p.getMenuItem().getMember() != null && p.getMenuItem().getMember().getGroup() != null)
                ? p.getMenuItem().getMember().getGroup().getId()
                : null;

        return ServicePaymentPlanDto.builder()
                .id(p.getId())
                .groupId(groupId)
                .menuItemId(menuItemId)
                .paymentForm(p.getPaymentForm() != null ? p.getPaymentForm().name() : null)
                .totalAmount(p.getTotalAmount())
                .currency(p.getCurrency())
                .records(records)
                .expenseRecords(List.of())
                .build();
    }
}
