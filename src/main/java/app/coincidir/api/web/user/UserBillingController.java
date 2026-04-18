package app.coincidir.api.web.user;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.user.dto.UserBillingDto;
import app.coincidir.api.web.user.dto.UserPaymentPlanDto;
import app.coincidir.api.web.user.dto.UserPaymentRecordDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/user/billing")
public class UserBillingController {

    private final TravelRequestRepository requestRepo;
    private final MemberPaymentPlanRepository planRepo;
    private final MemberPaymentRecordRepository recordRepo;

    public UserBillingController(TravelRequestRepository requestRepo,
                                 MemberPaymentPlanRepository planRepo,
                                 MemberPaymentRecordRepository recordRepo) {
        this.requestRepo = requestRepo;
        this.planRepo = planRepo;
        this.recordRepo = recordRepo;
    }

    @GetMapping("/me")
    public ResponseEntity<UserBillingDto> me(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }

        TravelRequest me = requestRepo.findTopByEmailOrderByIdDesc(principal.getName()).orElse(null);
        if (me == null) {
            return ResponseEntity.notFound().build();
        }

        TravelGroup group = me.getGroup();
        if (group == null) {
            return ResponseEntity.notFound().build();
        }

        Long groupId = group.getId();
        Long memberId = me.getId();

        MemberPaymentPlan plan = planRepo.findByGroupIdAndMemberId(groupId, memberId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        List<MemberPaymentRecord> records = recordRepo.findAllByPlanIdOrderByPaymentDateDescIdDesc(plan.getId());

        // Map plan
        UserPaymentPlanDto planDto = new UserPaymentPlanDto();
        planDto.planType = mapPlanType(plan.getPlanType());
        planDto.paymentMethod = (plan.getOneTimeMethod() == null ? null : plan.getOneTimeMethod().name());
        planDto.installmentCount = plan.getInstallments() == null ? 0 : plan.getInstallments().size();

        BigDecimal total = plan.getTotalAmount();
        if (total == null) {
            total = BigDecimal.ZERO;
            if (plan.getInstallments() != null) {
                for (var inst : plan.getInstallments()) {
                    if (inst.getAmount() != null) total = total.add(inst.getAmount());
                }
            }
        }
        planDto.totalToCollect = total;

        BigDecimal paid = BigDecimal.ZERO;
        for (MemberPaymentRecord r : records) {
            if (r.getAmount() != null) paid = paid.add(r.getAmount());
        }
        BigDecimal remaining = total.subtract(paid);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
        planDto.remainingBalance = remaining;
        planDto.hasAnyPayment = !records.isEmpty();
        planDto.isComplete = remaining.compareTo(BigDecimal.ZERO) == 0 && total.compareTo(BigDecimal.ZERO) > 0;

        // Map records
        List<UserPaymentRecordDto> paymentDtos = new ArrayList<>();
        for (MemberPaymentRecord r : records) {
            UserPaymentRecordDto rd = new UserPaymentRecordDto();
            rd.id = r.getId();
            rd.installmentNumber = r.getInstallmentNumber();
            rd.paymentDate = r.getPaymentDate();
            rd.amount = r.getAmount();
            rd.receiptLast4 = r.getReceiptLast4();
            paymentDtos.add(rd);
        }

        UserBillingDto out = new UserBillingDto();
        out.plan = planDto;
        out.payments = paymentDtos;
        return ResponseEntity.ok(out);
    }

    private String mapPlanType(PaymentPlanType type) {
        if (type == null) return null;
        if (type == PaymentPlanType.ONE_TIME) return "UN_PAGO";
        if (type == PaymentPlanType.OWN_FINANCING) return "FINANCIACION_PROPIA";
        return type.name();
    }
}
