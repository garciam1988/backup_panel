package app.coincidir.api.web.user.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class UserPaymentRecordDto {
    public Long id;
    public Integer installmentNumber;
    public LocalDate paymentDate;
    public BigDecimal amount;
    public String receiptLast4;
}
