package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.MemberPaymentPlanDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/members/{memberId}/payment-plan")
@RequiredArgsConstructor
public class MemberPaymentsAdminController {

    private final MemberPaymentsAdminService service;

    @GetMapping
    public MemberPaymentPlanDto get(
            @PathVariable Long groupId,
            @PathVariable Long memberId
    ) {
        return service.getPlan(groupId, memberId);
    }

    @PutMapping
    public MemberPaymentPlanDto put(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @RequestBody MemberPaymentPlanDto body
    ) {
        return service.upsertPlan(groupId, memberId, body);
    }
}
