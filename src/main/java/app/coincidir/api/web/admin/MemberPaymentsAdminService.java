package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.InstallmentStatus;
import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.PaymentOneTimeMethod;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.service.MemberPaymentsBootstrapService;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.admin.dto.MemberPaymentInstallmentDto;
import app.coincidir.api.web.admin.dto.MemberPaymentPlanDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemberPaymentsAdminService {

    private static final String TEMP_GROUP_DEST = "__TEMP_PAYMENTS__";

    private final MemberPaymentPlanRepository planRepo;
    private final TravelRequestRepository requestRepo;
    private final TravelGroupRepository groupRepo;
    private final MemberPaymentsBootstrapService paymentsBootstrapService;

    @Transactional
    public MemberPaymentPlanDto getPlan(Long groupId, Long memberId) {
        // groupId=0 => "sin grupo" (carga manual / unassigned)
        if (groupId != null && groupId == 0L) {
            // Solo lectura: devolver el plan ungrouped si existe.
            Optional<MemberPaymentPlan> opt = planRepo.findUngroupedWithInstallments(memberId);
            if (opt.isEmpty()) {
                return new MemberPaymentPlanDto(
                        null,
                        null,
                        memberId,
                        null,
                        null,
                        null,
                        "ARS",
                        null,
                        null,
                        List.of()
                );
            }
            return toDto(opt.get());
        }

        // validate membership (if member exists but isn't in that group -> 400)
        TravelRequest member = validateMemberBelongsToGroup(groupId, memberId);

        Optional<MemberPaymentPlan> opt = planRepo.findOneWithInstallments(groupId, memberId);
        if (opt.isEmpty()) {
            // Caso carga manual: el pago queda guardado en travel_request.deposit_* hasta que se materializa.
            // Si el plan todavía no existe, intentamos materializarlo al abrir la pantalla.
            try {
                paymentsBootstrapService.bootstrapFromRequestIfNeeded(member);
            } catch (Exception ignored) {
            }
            opt = planRepo.findOneWithInstallments(groupId, memberId);
        }

        if (opt.isEmpty()) {
            return new MemberPaymentPlanDto(
                    null,
                    groupId,
                    memberId,
                    null,
                    null,
                    null,
                    "ARS",
                    null,
                    null,
                    List.of()
            );
        }
        return toDto(opt.get());
    }

    @Transactional
    public MemberPaymentPlanDto upsertPlan(Long groupId, Long memberId, MemberPaymentPlanDto body) {
        if (groupId != null && groupId == 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se puede configurar plan sin grupo asignado");
        }
        TravelRequest member = validateMemberBelongsToGroup(groupId, memberId);

        // Validate DTO and normalize
        LocalDate maxDueDate = null;
        try {
            if (member != null && member.getGroup() != null) {
                if (member.getGroup().getTravelStartDate() != null) maxDueDate = member.getGroup().getTravelStartDate();
                else if (member.getGroup().getTravelEndDate() != null) maxDueDate = member.getGroup().getTravelEndDate();
            }
        } catch (Exception ignored) {
        }

        NormalizedPlan normalized = normalizeAndValidate(body, maxDueDate);

        MemberPaymentPlan plan = planRepo.findOneWithInstallments(groupId, memberId)
                .orElseGet(() -> MemberPaymentPlan.builder()
                        .groupId(groupId)
                        .memberId(memberId)
                        .installments(new ArrayList<>())
                        .currency("ARS")
                        .build());

        plan.setPlanType(normalized.planType);
        plan.setOneTimeMethod(normalized.oneTimeMethod);
        // Important:
        // - For ONE_TIME, totalAmount is defined when registering the payment.
        //   If the plan was previously OWN_FINANCING, it may still have a computed totalAmount.
        //   We must reset it to 0 so the ONE_TIME payment can be registered with any amount.
        // - For OWN_FINANCING, totalAmount is the sum of installments.
        if (normalized.planType == PaymentPlanType.ONE_TIME) {
            plan.setTotalAmount(normalized.totalAmount != null ? normalized.totalAmount : BigDecimal.ZERO);
        } else {
            plan.setTotalAmount(normalized.totalAmount != null ? normalized.totalAmount : BigDecimal.ZERO);
        }
        plan.setCurrency(normalized.currency);
        if (normalized.receiptLast4 != null) {
            plan.setReceiptLast4(normalized.receiptLast4);
        }
        plan.setNotes(normalized.notes);

        // Replace installments safely (avoid UNIQUE(plan_id, installment_number) collisions)
        if (plan.getId() != null) {
            plan.getInstallments().clear();
            planRepo.saveAndFlush(plan); // fuerza DELETE de orphans antes de INSERT
        } else {
            plan.getInstallments().clear();
        }

        for (MemberPaymentInstallmentDto it : normalized.installments) {
            MemberPaymentInstallment e = MemberPaymentInstallment.builder()
                    .plan(plan)
                    .installmentNumber(it.installmentNumber())
                    .amount(it.amount())
                    .dueDate(it.dueDate())
                    .paidDate(it.paidDate())
                    .status(parseInstallmentStatus(it.status()))
                    .build();
            plan.getInstallments().add(e);
        }

        MemberPaymentPlan saved = planRepo.save(plan);
        return toDto(saved);

    }

    /** Used by GroupAdminService to compute the green/red dot. */
    public static boolean isPaymentComplete(MemberPaymentPlan plan) {
        if (plan == null) return false;
        if (plan.getPlanType() == null) return false;

        if (plan.getPlanType() == PaymentPlanType.ONE_TIME) {
            return plan.getOneTimeMethod() != null;
        }

        // OWN_FINANCING
        if (plan.getInstallments() == null || plan.getInstallments().isEmpty()) return false;
        if (plan.getInstallments().size() > 10) return false;

        for (MemberPaymentInstallment it : plan.getInstallments()) {
            if (it.getInstallmentNumber() == null || it.getInstallmentNumber() < 1) return false;
            if (it.getAmount() == null || it.getAmount().compareTo(BigDecimal.ZERO) <= 0) return false;
            if (it.getDueDate() == null) return false;
        }
        return true;
    }

    /* ----------------------- helpers ----------------------- */

    private TravelRequest validateMemberBelongsToGroup(Long groupId, Long memberId) {
        TravelRequest r = requestRepo.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found: " + memberId));

        // groupId=0 => "sin grupo" (unassigned)
        if (groupId != null && groupId == 0L) {
            return r;
        }

        // Carga manual: el plan/pagos pueden estar materializados en __TEMP_PAYMENTS__ antes
        // de que la TravelRequest tenga group_id real.
        if ((r.getGroup() == null || r.getGroup().getId() == null) && isTempGroup(groupId)) {
            return r;
        }

        if (r.getGroup() == null || r.getGroup().getId() == null || !r.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Member " + memberId + " does not belong to group " + groupId);
        }
    
        return r;
    }

    private boolean isTempGroup(Long groupId) {
        if (groupId == null) return false;
        try {
            return groupRepo.findById(groupId)
                    .map(g -> {
                        String d = null;
                        try { d = g.getDestination(); } catch (Exception ignored) {}
                        return d != null && d.trim().equalsIgnoreCase(TEMP_GROUP_DEST);
                    })
                    .orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private record NormalizedPlan(
            PaymentPlanType planType,
            PaymentOneTimeMethod oneTimeMethod,
            BigDecimal totalAmount,
            String currency,
            String receiptLast4,
            String notes,
            List<MemberPaymentInstallmentDto> installments
    ) {}

    private NormalizedPlan normalizeAndValidate(MemberPaymentPlanDto body, LocalDate maxDueDate) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body is required");
        }

        PaymentPlanType planType = parsePlanType(body.planType());
        if (planType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planType is required");
        }

        String currency = (body.currency() == null || body.currency().isBlank()) ? "ARS" : body.currency().trim();
        String notes = body.notes();

        // In the new UI, receiptLast4 is captured when registering each payment, not when configuring the plan.
        // Keep it optional here for backwards compatibility.
        String last4 = body.receiptLast4() == null ? null : body.receiptLast4().trim();
        if (last4 != null && last4.isBlank()) last4 = null;

        List<MemberPaymentInstallmentDto> inst = body.installments() == null ? List.of() : body.installments();

        if (planType == PaymentPlanType.ONE_TIME) {
            PaymentOneTimeMethod method = parseOneTimeMethod(body.oneTimeMethod());
            if (method == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oneTimeMethod is required for ONE_TIME");
            }

            // totalAmount is defined when registering the ONE_TIME payment. If provided, accept only if > 0.
            BigDecimal total = body.totalAmount();
            if (total != null && total.compareTo(BigDecimal.ZERO) <= 0) {
                total = null;
            }

            // No installments required for ONE_TIME in the new UI.
            return new NormalizedPlan(planType, method, total, currency, last4, notes, List.of());
        }

        // OWN_FINANCING
        PaymentOneTimeMethod method = parseOneTimeMethod(body.oneTimeMethod());
        if (method == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oneTimeMethod is required for OWN_FINANCING");
        }

        if (inst.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "installments are required for OWN_FINANCING");
        }
        if (inst.size() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max installments is 10");
        }

        List<MemberPaymentInstallmentDto> normalized = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;

        // due_date es NOT NULL en DB: si la UI no lo manda, generamos vencimientos por defecto
        LocalDate baseDue = inst.get(0).dueDate() != null ? inst.get(0).dueDate() : LocalDate.now();

        for (int i = 0; i < inst.size(); i++) {
            MemberPaymentInstallmentDto it = inst.get(i);
            Integer n = it.installmentNumber();
            if (n == null) n = i + 1;
            if (n < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "installmentNumber must be >= 1");
            }
            if (it.amount() == null || it.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0 for each installment");
            }
            sum = sum.add(it.amount());

            LocalDate dueDate = it.dueDate();
            if (dueDate == null) {
                // cuota 1 => baseDue, cuota 2 => baseDue + 1 mes, etc.
                dueDate = baseDue.plusMonths(Math.max(0, n - 1));
            }

            if (maxDueDate != null && dueDate.isAfter(maxDueDate)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La fecha de la cuota " + n + " no puede ser mayor a la fecha de viaje (" + maxDueDate + ")");
            }

            normalized.add(new MemberPaymentInstallmentDto(
                    null,
                    n,
                    it.amount(),
                    dueDate,
                    it.paidDate(),
                    (it.status() == null || it.status().isBlank()) ? InstallmentStatus.PLANNED.name() : it.status()
            ));
        }

        // For OWN_FINANCING we can compute totalAmount as the sum of installment amounts.
        return new NormalizedPlan(planType, method, sum, currency, last4, notes, normalized);
    }


    private static PaymentPlanType parsePlanType(String v) {
        if (v == null || v.isBlank()) return null;
        try { return PaymentPlanType.valueOf(v.trim()); } catch (Exception e) { return null; }
    }

    private static PaymentOneTimeMethod parseOneTimeMethod(String v) {
        if (v == null || v.isBlank()) return null;
        try { return PaymentOneTimeMethod.valueOf(v.trim()); } catch (Exception e) { return null; }
    }

    private static InstallmentStatus parseInstallmentStatus(String v) {
        if (v == null || v.isBlank()) return InstallmentStatus.PLANNED;
        try { return InstallmentStatus.valueOf(v.trim()); } catch (Exception e) { return InstallmentStatus.PLANNED; }
    }

    private static MemberPaymentPlanDto toDto(MemberPaymentPlan plan) {
        List<MemberPaymentInstallmentDto> installments = plan.getInstallments() == null
                ? List.of()
                : plan.getInstallments().stream().map(i -> new MemberPaymentInstallmentDto(
                i.getId(),
                i.getInstallmentNumber(),
                i.getAmount(),
                i.getDueDate(),
                i.getPaidDate(),
                i.getStatus() != null ? i.getStatus().name() : null
        )).toList();

        return new MemberPaymentPlanDto(
                plan.getId(),
                plan.getGroupId(),
                plan.getMemberId(),
                plan.getPlanType() != null ? plan.getPlanType().name() : null,
                plan.getOneTimeMethod() != null ? plan.getOneTimeMethod().name() : null,
                plan.getTotalAmount(),
                plan.getCurrency(),
                plan.getReceiptLast4(),
                plan.getNotes(),
                installments
        );
    }
}