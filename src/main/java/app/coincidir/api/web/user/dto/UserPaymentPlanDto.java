package app.coincidir.api.web.user.dto;

import java.math.BigDecimal;

public class UserPaymentPlanDto {
    /**
     * UN_PAGO | FINANCIACION_PROPIA
     */
    public String planType;

    /**
     * TRANSFERENCIA | ... (mismo valor que se carga en admin)
     */
    public String paymentMethod;

    public Integer installmentCount;
    public BigDecimal totalToCollect;
    public BigDecimal remainingBalance;
    public Boolean hasAnyPayment;
    public Boolean isComplete;
}
