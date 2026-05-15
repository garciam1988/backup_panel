package app.coincidir.api.web.user.dto;

import java.util.ArrayList;
import java.util.List;

public class UserBillingDto {
    public UserPaymentPlanDto plan;
    public List<UserPaymentRecordDto> payments = new ArrayList<>();
}
