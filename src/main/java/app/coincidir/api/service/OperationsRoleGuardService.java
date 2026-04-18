package app.coincidir.api.service;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.web.admin.MemberPaymentsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperationsRoleGuardService {

    private final TravelGroupRepository groupRepo;
    private final MemberPaymentPlanRepository paymentPlanRepo;
    private final MemberPaymentRecordRepository paymentRecordRepo;
    private final ExpenseRepository expenseRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;

    public boolean restrictToPaidConfirmed() {
        Authentication auth = SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null;
        if (auth == null || auth.getAuthorities() == null) return false;

        boolean admin = hasRole(auth, "ADMIN");
        boolean operations = hasRole(auth, "OPERATIONS");
        return operations && !admin;
    }

    public void requirePaidAndConfirmed(Long groupId) {
        if (!restrictToPaidConfirmed()) return;

        if (groupId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupId is required");
        }

        TravelGroup g = groupRepo.fetchDetail(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo no encontrado"));

        if (!g.isOperationConfirmed() || !areAllMemberPaymentsVerified(g)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado para ver esta operación");
        }
    }

    public boolean isPaidAndConfirmed(TravelGroup g) {
        return g != null && g.isOperationConfirmed() && areAllMemberPaymentsVerified(g);
    }

    /**
     * Pagos completos = todos los miembros con plan completo + al menos un pago registrado
     * y TODOS los pagos registrados conciliados como VERIFIED.
     */
    private boolean areAllMemberPaymentsVerified(TravelGroup g) {
        if (g == null || g.getId() == null) return false;

        // Si llega el grupo sin members cargados (lazy), re-fetch con join fetch.
        List<TravelRequest> members = g.getMembers();
        if (members == null || members.isEmpty()) {
            try {
                TravelGroup refreshed = groupRepo.fetchDetail(g.getId()).orElse(null);
                if (refreshed != null) members = refreshed.getMembers();
            } catch (Exception ignore) {}
        }
        if (members == null || members.isEmpty()) return false;

        Long groupId = g.getId();

        List<MemberPaymentPlan> plans = paymentPlanRepo.findAllByGroupIdWithInstallments(groupId);
        Map<Long, MemberPaymentPlan> planByMemberId = plans.stream()
                .filter(p -> p != null && p.getMemberId() != null)
                .collect(Collectors.toMap(MemberPaymentPlan::getMemberId, p -> p, (a, b) -> a));

        List<MemberPaymentRecord> records = paymentRecordRepo.findAllByGroupIdOrderByPaymentDateDescIdDesc(groupId);
        Map<Long, List<MemberPaymentRecord>> recordsByMemberId = records.stream()
                .filter(r -> r != null && r.getMemberId() != null)
                .collect(Collectors.groupingBy(MemberPaymentRecord::getMemberId));

        Set<Long> recordIds = records.stream()
                .map(MemberPaymentRecord::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, FinancialMovementConciliation> concByRecordId;
        if (recordIds.isEmpty()) {
            concByRecordId = Map.of();
        } else {
            concByRecordId = conciliationRepo
                    .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, recordIds)
                    .stream()
                    .filter(c -> c != null && c.getMovementId() != null)
                    .collect(Collectors.toMap(FinancialMovementConciliation::getMovementId, c -> c, (a, b) -> a));
        }

        for (TravelRequest m : members) {
            if (m == null || m.getId() == null) return false;
            Long memberId = m.getId();

            MemberPaymentPlan plan = planByMemberId.get(memberId);
            if (plan == null) return false;
            if (!MemberPaymentsAdminService.isPaymentComplete(plan)) return false;

            List<MemberPaymentRecord> memberRecords = recordsByMemberId.get(memberId);
            if (memberRecords == null || memberRecords.isEmpty()) return false;

            Long planId = null;
            try { planId = plan.getId(); } catch (Exception ignore) {}

            int checked = 0;
            for (MemberPaymentRecord r : memberRecords) {
                if (r == null || r.getId() == null) continue;

                // defensive: validate record belongs to this plan
                try {
                    if (r.getPlan() != null && planId != null && r.getPlan().getId() != null
                            && !r.getPlan().getId().equals(planId)) {
                        continue;
                    }
                } catch (Exception ignore) {
                    // ignore
                }

                checked++;
                // Require conciliation row present and VERIFIED (confirmado) in Check Panel.
                FinancialMovementConciliation c = concByRecordId.get(r.getId());
                if (c == null || c.getStatus() != ConciliationStatus.VERIFIED) return false;
            }

            if (checked == 0) return false;
        }

        return true;
    }

    private boolean hasRole(Authentication auth, String role) {
        String expected = "ROLE_" + role;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga == null) continue;
            String a = ga.getAuthority();
            if (a != null && a.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
