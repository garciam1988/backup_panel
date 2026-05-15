package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.InstallmentStatus;
import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.service.AuthorizationCodeService;
import app.coincidir.api.web.admin.dto.CreateMemberPaymentRecordRequest;
import app.coincidir.api.web.admin.dto.MemberPaymentRecordDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MemberPaymentRecordsAdminService {

    private static final String TEMP_GROUP_DEST = "__TEMP_PAYMENTS__";

    private final MemberPaymentPlanRepository planRepo;
    private final MemberPaymentRecordRepository recordRepo;
    private final TravelRequestRepository requestRepo;
    private final TravelGroupRepository groupRepo;
    private final AuthorizationCodeService authCodeService;

    @Transactional(readOnly = true)
    public List<MemberPaymentRecordDto> list(Long groupId, Long memberId) {
        validateMemberBelongsToGroup(groupId, memberId);

        // Map of installmentNumber -> dueDate (for OWN_FINANCING)
        final Map<Integer, LocalDate> dueDates = loadDueDates(groupId, memberId);

        // groupId=0 => "sin grupo" (unassigned)
        if (groupId != null && groupId == 0L) {
            return recordRepo.findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(memberId)
                    .stream()
                    .map(r -> toDto(r, dueDates))
                    .toList();
        }

        return recordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId)
                .stream()
                .map(r -> toDto(r, dueDates))
                .toList();
    }

    @Transactional
    public MemberPaymentRecordDto uploadReceipt(Long groupId, Long memberId, Long paymentId,
                                                org.springframework.web.multipart.MultipartFile file) {
        validateMemberBelongsToGroup(groupId, memberId);
        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }

        MemberPaymentRecord record = recordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        Long expectedGroupId = (groupId != null && groupId == 0L) ? null : groupId;
        if (!java.util.Objects.equals(record.getGroupId(), expectedGroupId) || !java.util.Objects.equals(record.getMemberId(), memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId);
        }

// Comprobante obligatorio
if (file == null || file.isEmpty()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El comprobante es obligatorio");
}

        try {
            if (file != null && !file.isEmpty()) {
                byte[] bytes = file.getBytes();
                if (bytes != null && bytes.length > 0) {
                    record.setReceiptBlob(bytes);
                    String ct = file.getContentType();
                    if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                    record.setReceiptContentType(ct);
                    record.setReceiptFileName(file.getOriginalFilename());
                    recordRepo.save(record);
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }

        return toDto(record, loadDueDates(groupId, memberId));
    }

    @Transactional(readOnly = true)
    public ReceiptDownload downloadReceipt(Long groupId, Long memberId, Long paymentId) {
        validateMemberBelongsToGroup(groupId, memberId);
        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }

        MemberPaymentRecord record = recordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        Long expectedGroupId = (groupId != null && groupId == 0L) ? null : groupId;
        if (!java.util.Objects.equals(record.getGroupId(), expectedGroupId) || !java.util.Objects.equals(record.getMemberId(), memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId);
        }

        byte[] bytes = record.getReceiptBlob();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }
        String ct = record.getReceiptContentType();
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
        String fn = record.getReceiptFileName();
        if (fn == null || fn.isBlank()) fn = "comprobante";
        return new ReceiptDownload(bytes, ct, fn);
    }

    @Transactional
    public void delete(Long groupId, Long memberId, Long paymentId) {
        // Acción sensible: requiere autorización si el usuario NO es ADMIN
        authCodeService.requireAuthorizationIfNotAdmin("ADMIN");
        validateMemberBelongsToGroup(groupId, memberId);

        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }

        MemberPaymentRecord record = recordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        Long expectedGroupId2 = (groupId != null && groupId == 0L) ? null : groupId;
        if (!Objects.equals(record.getGroupId(), expectedGroupId2) || !Objects.equals(record.getMemberId(), memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId);
        }

        MemberPaymentPlan plan = (groupId != null && groupId == 0L)
                ? planRepo.findUngroupedWithInstallments(memberId).orElseGet(record::getPlan)
                : planRepo.findOneWithInstallments(groupId, memberId).orElseGet(record::getPlan);

        PaymentPlanType planType = null;
        try {
            if (plan != null && plan.getPlanType() != null) {
                planType = plan.getPlanType();
            }
        } catch (Exception ignored) {
        }

        Integer installmentNumber = record.getInstallmentNumber() == null ? 1 : record.getInstallmentNumber();

        // Delete payment record first
        recordRepo.delete(record);

        // Roll back installment state (and allow re-registering with a different amount for ONE_TIME)
        if (planType == PaymentPlanType.ONE_TIME) {
            try {
                if (plan != null) {
                    plan.setTotalAmount(BigDecimal.ZERO);
                    if (plan.getInstallments() != null) {
                        for (MemberPaymentInstallment it : plan.getInstallments()) {
                            if (it == null) continue;
                            if (Objects.equals(it.getInstallmentNumber(), 1)) {
                                it.setPaidDate(null);
                                it.setStatus(InstallmentStatus.PLANNED);
                            }
                        }
                    }
                    // keep method/currency as configured
                    // Ensure the reset is persisted even if the plan was reached via record.getPlan()
                    planRepo.save(plan);
                }
            } catch (Exception ignored) {
            }
            return;
        }

        if (planType == PaymentPlanType.OWN_FINANCING) {
            if (plan == null || plan.getInstallments() == null) return;
            for (MemberPaymentInstallment it : plan.getInstallments()) {
                if (it == null) continue;
                if (Objects.equals(it.getInstallmentNumber(), installmentNumber)) {
                    it.setPaidDate(null);
                    it.setStatus(InstallmentStatus.PLANNED);
                }
            }
        }
    }

    @Transactional
    public MemberPaymentRecordDto create(Long groupId, Long memberId, CreateMemberPaymentRecordRequest body) {
        validateMemberBelongsToGroup(groupId, memberId);

        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body is required");
        }
        BigDecimal amount = body.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0");
        }
        LocalDate paymentDate = body.paymentDate();
        if (paymentDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentDate is required");
        }
        String last4 = body.receiptLast4() == null ? null : body.receiptLast4().trim();
        if (last4 == null || !last4.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receiptLast4 must be 4 digits");
        }

        MemberPaymentPlan plan = (groupId != null && groupId == 0L)
                ? planRepo.findUngroupedWithInstallments(memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment plan is not configured yet"))
                : planRepo.findOneWithInstallments(groupId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment plan is not configured yet"));
        if (plan.getPlanType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment plan is not configured yet");
        }

        Integer installmentNumber;

        if (plan.getPlanType() == PaymentPlanType.ONE_TIME) {
            installmentNumber = 1;
            if (recordRepo.existsByPlanId(plan.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already registered for this ONE_TIME plan");
            }

            // ONE_TIME: In the current UI, the amount is defined when registering the payment (not when configuring the plan).
            // The DB/JPA model keeps totalAmount as NOT NULL, and many existing rows store 0.00.
            // Behavior:
            //  - If totalAmount is null/<=0 => treat it as "not configured" and set it from this payment.
            //  - If totalAmount > 0        => enforce exact match.
            if (plan.getTotalAmount() == null || plan.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                plan.setTotalAmount(amount);
                planRepo.save(plan);
            } else if (plan.getTotalAmount().compareTo(amount) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must match plan totalAmount for ONE_TIME");
            }

            // Also mark the synthetic installment as paid (if present)
            markInstallmentPaid(plan, 1, paymentDate);
        } else {
            // OWN_FINANCING
            List<MemberPaymentInstallment> inst = plan.getInstallments() == null ? List.of() : plan.getInstallments();
            if (inst.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment plan installments are not configured yet");
            }

            // Determine next unpaid installment based on records
            Set<Integer> paidNumbers = new HashSet<>(
                    recordRepo.findAllByPlanIdOrderByPaymentDateDescIdDesc(plan.getId())
                            .stream()
                            .map(MemberPaymentRecord::getInstallmentNumber)
                            .filter(Objects::nonNull)
                            .toList()
            );

            Integer next = null;
            for (int i = 1; i <= inst.size(); i++) {
                if (!paidNumbers.contains(i)) {
                    next = i;
                    break;
                }
            }
            if (next == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "All installments are already paid");
            }

            Integer requested = body.installmentNumber();
            if (requested == null) {
                requested = next;
            }
            if (!requested.equals(next)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "installmentNumber must be the next pending installment: " + next);
            }
            installmentNumber = requested;

            // Validate amount matches planned installment amount
            BigDecimal plannedAmount = inst.get(installmentNumber - 1).getAmount();
            if (plannedAmount != null && plannedAmount.compareTo(amount) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "amount must match the planned installment amount for installment #" + installmentNumber);
            }

            if (recordRepo.existsByPlanIdAndInstallmentNumber(plan.getId(), installmentNumber)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This installment is already paid");
            }

            markInstallmentPaid(plan, installmentNumber, paymentDate);
        }


// Cuota 1: no puede tener fecha mayor a hoy
if (installmentNumber != null && installmentNumber == 1) {
    LocalDate today = LocalDate.now();
    if (paymentDate.isAfter(today)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de la cuota 1 no puede ser mayor a la fecha de hoy");
    }
}
        Long effectiveGroupId = (groupId != null && groupId == 0L) ? null : groupId;
        MemberPaymentRecord record = MemberPaymentRecord.builder()
                .plan(plan)
                .groupId(effectiveGroupId)
                .memberId(memberId)
                .installmentNumber(installmentNumber)
                .amount(amount)
                .currency(plan.getCurrency() == null || plan.getCurrency().isBlank() ? "ARS" : plan.getCurrency())
                .paymentDate(paymentDate)
                .receiptLast4(last4)
                .build();

        MemberPaymentRecord saved = recordRepo.save(record);
        Map<Integer, LocalDate> dueDates = new HashMap<>();
        try {
            if (plan.getInstallments() != null) {
                for (MemberPaymentInstallment it : plan.getInstallments()) {
                    if (it == null || it.getInstallmentNumber() == null) continue;
                    dueDates.put(it.getInstallmentNumber(), it.getDueDate());
                }
            }
        } catch (Exception ignored) {
        }
        return toDto(saved, dueDates);
    }

    /* ----------------------- helpers ----------------------- */

    private void markInstallmentPaid(MemberPaymentPlan plan, int installmentNumber, LocalDate paymentDate) {
        if (plan.getInstallments() == null) return;
        for (MemberPaymentInstallment it : plan.getInstallments()) {
            if (it.getInstallmentNumber() != null && it.getInstallmentNumber() == installmentNumber) {
                it.setStatus(InstallmentStatus.PAID);
                it.setPaidDate(paymentDate);
                return;
            }
        }
    }

    private void validateMemberBelongsToGroup(Long groupId, Long memberId) {
        TravelRequest r = requestRepo.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found: " + memberId));

        // groupId=0 => "sin grupo" (unassigned)
        if (groupId != null && groupId == 0L) {
            return;
        }

        // Carga manual: el pago puede estar materializado en el grupo temporal __TEMP_PAYMENTS__
        // incluso cuando la TravelRequest todavía no tiene group_id real.
        if ((r.getGroup() == null || r.getGroup().getId() == null) && isTempGroup(groupId)) {
            return;
        }
        if (r.getGroup() == null || r.getGroup().getId() == null || !r.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Member " + memberId + " does not belong to group " + groupId);
        }
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

    private Map<Integer, LocalDate> loadDueDates(Long groupId, Long memberId) {
        final Map<Integer, LocalDate> dueDates = new HashMap<>();
        try {
            if (groupId != null && groupId == 0L) {
                planRepo.findUngroupedWithInstallments(memberId).ifPresent(plan -> {
                    if (plan.getInstallments() == null) return;
                    for (MemberPaymentInstallment it : plan.getInstallments()) {
                        if (it == null) continue;
                        Integer n = it.getInstallmentNumber();
                        if (n == null) continue;
                        dueDates.put(n, it.getDueDate());
                    }
                });
            } else {
                planRepo.findOneWithInstallments(groupId, memberId).ifPresent(plan -> {
                if (plan.getInstallments() == null) return;
                for (MemberPaymentInstallment it : plan.getInstallments()) {
                    if (it == null) continue;
                    Integer n = it.getInstallmentNumber();
                    if (n == null) continue;
                    dueDates.put(n, it.getDueDate());
                }
                });
            }
        } catch (Exception ignored) {
        }
        return dueDates;
    }

    public record ReceiptDownload(byte[] bytes, String contentType, String fileName) {}

    private MemberPaymentRecordDto toDto(MemberPaymentRecord r, Map<Integer, LocalDate> dueDates) {
        String planType = null;
        try {
            if (r.getPlan() != null && r.getPlan().getPlanType() != null) {
                planType = r.getPlan().getPlanType().name();
            }
        } catch (Exception ignored) {
        }

        LocalDate dueDate = null;
        try {
            Integer n = r.getInstallmentNumber();
            if (n != null && dueDates != null) {
                dueDate = dueDates.get(n);
            }
        } catch (Exception ignored) {
        }

        boolean hasReceipt = false;
        try {
            byte[] b = r.getReceiptBlob();
            hasReceipt = b != null && b.length > 0;
        } catch (Exception ignored) {
        }

        return new MemberPaymentRecordDto(
                r.getId(),
                r.getGroupId(),
                r.getMemberId(),
                planType,
                r.getInstallmentNumber(),
                dueDate,
                r.getAmount(),
                r.getCurrency(),
                r.getPaymentDate(),
                r.getReceiptLast4(),
                r.getCreatedAt(),
                hasReceipt
        );
    }
}
