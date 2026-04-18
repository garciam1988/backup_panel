package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.InstallmentStatus;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.GroupOperationsRepository;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.web.admin.dto.PendingOwnFinancingInstallmentRowDto;
import app.coincidir.api.web.admin.dto.InstallmentCollectionRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OwnFinancingAdminService {

    private final MemberPaymentPlanRepository planRepo;
    private final MemberPaymentRecordRepository recordRepo;
    private final TravelGroupRepository groupRepo;
    private final TravelRequestRepository requestRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;
    private final GroupOperationsRepository groupOperationsRepo;

    @Transactional(readOnly = true)
    public List<PendingOwnFinancingInstallmentRowDto> listPendingInstallments() {
        List<MemberPaymentPlan> plans = planRepo.findAllByPlanTypeWithInstallments(PaymentPlanType.OWN_FINANCING);
        if (plans == null || plans.isEmpty()) return List.of();

        // Solo planes asociados a un grupo real
        List<MemberPaymentPlan> groupPlans = plans.stream()
                .filter(p -> p != null && p.getId() != null && p.getGroupId() != null && p.getMemberId() != null)
                .toList();

        if (groupPlans.isEmpty()) return List.of();

        List<Long> planIds = groupPlans.stream().map(MemberPaymentPlan::getId).toList();

        // Cargar pagos para calcular próximo nro de cuota según la misma regla que usa el alta de pago
        List<MemberPaymentRecord> records = recordRepo.findAllByPlanIdIn(planIds);

        Map<Long, Set<Integer>> paidNumbersByPlan = new HashMap<>();
        Map<Long, LocalDate> lastPaymentByPlan = new HashMap<>();

        if (records != null) {
            for (MemberPaymentRecord r : records) {
                if (r == null || r.getPlan() == null || r.getPlan().getId() == null) continue;
                Long pid = r.getPlan().getId();
                Integer n = r.getInstallmentNumber();
                if (n != null) {
                    paidNumbersByPlan.computeIfAbsent(pid, k -> new HashSet<>()).add(n);
                }
                LocalDate pd = r.getPaymentDate();
                if (pd != null) {
                    LocalDate prev = lastPaymentByPlan.get(pid);
                    if (prev == null || pd.isAfter(prev)) lastPaymentByPlan.put(pid, pd);
                }
            }
        }

        // Enriquecimiento: grupos y miembros
        Set<Long> groupIds = new HashSet<>();
        Set<Long> memberIds = new HashSet<>();
        for (MemberPaymentPlan p : groupPlans) {
            groupIds.add(p.getGroupId());
            memberIds.add(p.getMemberId());
        }

        Map<Long, TravelGroup> groupById = new HashMap<>();
        for (TravelGroup g : groupRepo.findAllById(groupIds)) {
            if (g != null && g.getId() != null) groupById.put(g.getId(), g);
        }

        Map<Long, TravelRequest> memberById = new HashMap<>();
        for (TravelRequest r : requestRepo.findAllById(memberIds)) {
            if (r != null && r.getId() != null) memberById.put(r.getId(), r);
        }

        List<PendingOwnFinancingInstallmentRowDto> out = new ArrayList<>();

        for (MemberPaymentPlan plan : groupPlans) {
            List<MemberPaymentInstallment> installments = plan.getInstallments() == null ? List.of() : plan.getInstallments();
            int total = installments.size();
            if (total <= 0) continue;

            Set<Integer> paid = paidNumbersByPlan.getOrDefault(plan.getId(), Set.of());
            int paidCount = 0;
            Integer next = null;
            for (int i = 1; i <= total; i++) {
                if (paid.contains(i)) {
                    paidCount++;
                } else if (next == null) {
                    next = i;
                }
            }
            int pending = total - paidCount;
            if (pending <= 0 || next == null) continue;

            MemberPaymentInstallment nextInst = (next - 1) < installments.size() ? installments.get(next - 1) : null;
            LocalDate nextDue = nextInst != null ? nextInst.getDueDate() : null;
            BigDecimal nextAmount = nextInst != null ? nextInst.getAmount() : null;

            TravelGroup g = groupById.get(plan.getGroupId());
            TravelRequest m = memberById.get(plan.getMemberId());

            // Si el miembro ya no pertenece al grupo, lo ocultamos para no habilitar cobro inválido.
            if (m != null && m.getGroup() != null && m.getGroup().getId() != null) {
                if (!Objects.equals(m.getGroup().getId(), plan.getGroupId())) {
                    continue;
                }
            }

            String destination = g != null ? g.getDestination() : null;
            String whenLabel = g != null ? g.getWhenLabel() : null;
            String memberName = m != null ? m.getName() : null;

            out.add(new PendingOwnFinancingInstallmentRowDto(
                    plan.getGroupId(),
                    destination,
                    whenLabel,
                    plan.getMemberId(),
                    memberName,
                    total,
                    paidCount,
                    pending,
                    next,
                    nextDue,
                    nextAmount,
                    (plan.getCurrency() == null || plan.getCurrency().isBlank()) ? "ARS" : plan.getCurrency(),
                    lastPaymentByPlan.get(plan.getId())
            ));
        }

        out.sort(Comparator
                .comparing(PendingOwnFinancingInstallmentRowDto::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PendingOwnFinancingInstallmentRowDto::groupId, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PendingOwnFinancingInstallmentRowDto::memberId, Comparator.nullsLast(Comparator.naturalOrder())));

        return out;
    }


    /* -------------------- Cobro de cuotas (titulares) -------------------- */

    @Transactional(readOnly = true)
    public List<InstallmentCollectionRowDto> listInstallmentsCollections() {
        List<MemberPaymentPlan> plans = planRepo.findAllByPlanTypeWithInstallments(PaymentPlanType.OWN_FINANCING);
        if (plans == null || plans.isEmpty()) return List.of();

        List<MemberPaymentPlan> groupPlans = plans.stream()
                .filter(p -> p != null && p.getId() != null && p.getGroupId() != null && p.getMemberId() != null)
                .toList();
        if (groupPlans.isEmpty()) return List.of();

        List<Long> planIds = groupPlans.stream().map(MemberPaymentPlan::getId).toList();

        List<MemberPaymentRecord> records = recordRepo.findAllByPlanIdIn(planIds);

        // key: planId|installmentNumber
        Map<String, MemberPaymentRecord> recordByPlanAndNum = new HashMap<>();
        List<Long> paymentIds = new ArrayList<>();
        if (records != null) {
            for (MemberPaymentRecord r : records) {
                if (r == null || r.getId() == null || r.getPlan() == null || r.getPlan().getId() == null) continue;
                Integer n = r.getInstallmentNumber();
                if (n == null) continue;
                String key = r.getPlan().getId() + "|" + n;
                // prefer newest record if duplicates (shouldn't happen, but be defensive)
                MemberPaymentRecord prev = recordByPlanAndNum.get(key);
                if (prev == null || (r.getPaymentDate() != null && prev.getPaymentDate() != null && r.getPaymentDate().isAfter(prev.getPaymentDate()))
                        || (prev.getId() != null && r.getId() != null && r.getId() > prev.getId())) {
                    recordByPlanAndNum.put(key, r);
                }
                paymentIds.add(r.getId());
            }
        }

        Map<Long, FinancialMovementConciliation> conciliationByPaymentId = new HashMap<>();
        if (!paymentIds.isEmpty()) {
            try {
                for (FinancialMovementConciliation c : conciliationRepo.findAllByMovementTypeAndMovementIdIn(
                        FinancialMovementType.MEMBER_PAYMENT_RECORD,
                        paymentIds
                )) {
                    if (c == null || c.getMovementId() == null) continue;
                    conciliationByPaymentId.put(c.getMovementId(), c);
                }
            } catch (Exception ignored) {
            }
        }

        // Enriquecimiento: grupos y titulares
        Set<Long> groupIds = new HashSet<>();
        Set<Long> memberIds = new HashSet<>();
        for (MemberPaymentPlan p : groupPlans) {
            groupIds.add(p.getGroupId());
            memberIds.add(p.getMemberId());
        }

        // Operación (GroupOperations) por grupo, para mostrar ID Op. en grilla de cobro
        Map<Long, Long> operationIdByGroupId = new HashMap<>();
        if (!groupIds.isEmpty()) {
            try {
                groupOperationsRepo.findAllByGroupIdIn(groupIds).forEach(op -> {
                    if (op != null && op.getGroup() != null && op.getGroup().getId() != null && op.getId() != null) {
                        operationIdByGroupId.put(op.getGroup().getId(), op.getId());
                    }
                });
            } catch (Exception ignored) {
            }
        }

        Map<Long, TravelGroup> groupById = new HashMap<>();
        for (TravelGroup g : groupRepo.findAllById(groupIds)) {
            if (g != null && g.getId() != null) groupById.put(g.getId(), g);
        }

        Map<Long, TravelRequest> memberById = new HashMap<>();
        for (TravelRequest r : requestRepo.findAllById(memberIds)) {
            if (r != null && r.getId() != null) memberById.put(r.getId(), r);
        }

        List<InstallmentCollectionRowDto> out = new ArrayList<>();

        for (MemberPaymentPlan plan : groupPlans) {
            List<MemberPaymentInstallment> installments = plan.getInstallments() == null ? List.of() : plan.getInstallments();
            int total = installments.size();
            if (total <= 0) continue;

            TravelGroup g = groupById.get(plan.getGroupId());
            TravelRequest m = memberById.get(plan.getMemberId());

            // Si el titular ya no pertenece al grupo, no habilitar cobro
            if (m != null && m.getGroup() != null && m.getGroup().getId() != null) {
                if (!Objects.equals(m.getGroup().getId(), plan.getGroupId())) {
                    continue;
                }
            }

            String destination = g != null ? g.getDestination() : null;
            String memberName = m != null ? m.getName() : null;
            String whatsapp = m != null ? m.getPhone() : null;
            String currency = (plan.getCurrency() == null || plan.getCurrency().isBlank()) ? "ARS" : plan.getCurrency();

            for (MemberPaymentInstallment it : installments) {
                if (it == null || it.getInstallmentNumber() == null) continue;

                Integer num = it.getInstallmentNumber();
                String status;

                // Estado del flujo (no confundir con InstallmentStatus PLANNED/PAID)
                if (it.getStatus() == InstallmentStatus.PAID) {
                    MemberPaymentRecord r = recordByPlanAndNum.get(plan.getId() + "|" + num);
                    ConciliationStatus cs = null;
                    if (r != null && r.getId() != null) {
                        FinancialMovementConciliation c = conciliationByPaymentId.get(r.getId());
                        if (c != null) cs = c.getStatus();
                    }
                    if (cs == ConciliationStatus.VERIFIED) {
                        status = "COLLECTED";
                    } else {
                        status = "COLLECTED_SC";
                    }
                } else {
                    LocalDateTime notifiedAt = null;
                    try {
                        notifiedAt = it.getCollectionNotifiedAt();
                    } catch (Exception ignored) {
                    }
                    status = (notifiedAt != null) ? "PENDING_PAYMENT" : "UPCOMING";
                }

                out.add(new InstallmentCollectionRowDto(
                        operationIdByGroupId.get(plan.getGroupId()),
                        plan.getGroupId(),
                        plan.getMemberId(),
                        memberName,
                        destination,
                        num,
                        total,
                        it.getDueDate(),
                        it.getAmount(),
                        currency,
                        whatsapp,
                        status
                ));
            }
        }

        out.sort(Comparator
                .comparing(InstallmentCollectionRowDto::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InstallmentCollectionRowDto::groupId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InstallmentCollectionRowDto::memberId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InstallmentCollectionRowDto::installmentNumber, Comparator.nullsLast(Comparator.naturalOrder()))
        );

        return out;
    }

    @Transactional
    public void markInstallmentNotified(Long groupId, Long memberId, Integer installmentNumber) {
        if (groupId == null || memberId == null) return;
        if (installmentNumber == null || installmentNumber <= 0) return;

        MemberPaymentPlan plan = planRepo.findOneWithInstallments(groupId, memberId).orElse(null);
        if (plan == null || plan.getPlanType() != PaymentPlanType.OWN_FINANCING) return;

        if (plan.getInstallments() == null) return;
        for (MemberPaymentInstallment it : plan.getInstallments()) {
            if (it == null || it.getInstallmentNumber() == null) continue;
            if (!Objects.equals(it.getInstallmentNumber(), installmentNumber)) continue;

            // si ya está paga, no tiene sentido notificar
            if (it.getStatus() == InstallmentStatus.PAID) return;

            it.setCollectionNotifiedAt(LocalDateTime.now());
            return;
        }
    }

    @Transactional
    public void markInstallmentCollectedSc(Long groupId, Long memberId, Integer installmentNumber) {
        if (groupId == null || memberId == null) return;
        if (installmentNumber == null || installmentNumber <= 0) return;

        MemberPaymentPlan plan = planRepo.findOneWithInstallments(groupId, memberId).orElse(null);
        if (plan == null || plan.getPlanType() != PaymentPlanType.OWN_FINANCING) return;

        // best-effort: limpiar marca de notificación para que no vuelva a mostrar "Pendiente de pago"
        if (plan.getInstallments() == null) return;
        for (MemberPaymentInstallment it : plan.getInstallments()) {
            if (it == null || it.getInstallmentNumber() == null) continue;
            if (!Objects.equals(it.getInstallmentNumber(), installmentNumber)) continue;

            try {
                it.setCollectionNotifiedAt(null);
            } catch (Exception ignored) {
            }
            return;
        }
    }
}
