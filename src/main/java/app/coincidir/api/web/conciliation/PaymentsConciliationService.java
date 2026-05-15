package app.coincidir.api.web.conciliation;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.MemberOptionalServiceCode;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.PaymentOneTimeMethod;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.OptionalServicePaymentRecordRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.conciliation.dto.ConciliationPaymentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentsConciliationService {

    private final MemberPaymentRecordRepository paymentRecordRepo;
    private final MemberPaymentPlanRepository paymentPlanRepo;
    private final TravelRequestRepository travelRequestRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;
    private final OptionalServicePaymentRecordRepository optionalServicePaymentRecordRepo;

    private static String formatOneTimeMethod(PaymentOneTimeMethod method) {
        if (method == null) return null;
        return switch (method) {
            case EFECTIVO -> "Efectivo";
            case TRANSFERENCIA -> "Transferencia";
            case DEPOSITO -> "Deposito";
            case TARJETA_DEBITO -> "Tarjeta Debito";
            case TARJETA_CREDITO -> "Tarjeta Credito";
        };
    }

    private static boolean isExcursionesOptionalPayment(MemberPaymentPlan plan) {
        if (plan == null) return false;
        String n;
        try {
            n = plan.getNotes();
        } catch (Exception e) {
            n = null;
        }
        if (n == null || n.isBlank()) return false;
        String x = n.toLowerCase(java.util.Locale.ROOT);
        // Ser conservadores: solo marcar como Excursiones cuando viene del espejo de Operations.
        return x.contains("pago adicional excursiones")
                || x.contains("excursiones (operations)")
                || x.contains("pago adicional excursion");
    }

    private static String stripPagoPrefix(String name) {
        if (name == null) return null;
        String t = name.trim();
        if (t.isEmpty()) return t;
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("pago:")) {
            return t.substring("pago:".length()).trim();
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<ConciliationPaymentDto> listPayments() {
        List<MemberPaymentRecord> records = paymentRecordRepo.findAll(
                Sort.by(Sort.Order.desc("paymentDate"), Sort.Order.desc("id"))
        );        if (records == null || records.isEmpty()) return List.of();

        // Pre-cargar planes con cuotas para poder exponer installmentsTotal sin N+1
        List<Long> planIds = records.stream()
                .map(r -> r != null && r.getPlan() != null ? r.getPlan().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, MemberPaymentPlan> planById = new HashMap<>();
        if (!planIds.isEmpty()) {
            for (MemberPaymentPlan p : paymentPlanRepo.findAllByIdInWithInstallments(planIds)) {
                if (p == null || p.getId() == null) continue;
                planById.put(p.getId(), p);
            }
        }

        List<Long> paymentIds = records.stream()
                .map(MemberPaymentRecord::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, FinancialMovementConciliation> conciliationByPaymentId = conciliationRepo
                .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentIds)
                .stream()
                .collect(Collectors.toMap(
                        FinancialMovementConciliation::getMovementId,
                        c -> c,
                        (a, b) -> a
                ));

        Set<Long> memberIds = records.stream()
                .map(MemberPaymentRecord::getMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> groupIds = records.stream()
                .map(MemberPaymentRecord::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> memberNameById = new HashMap<>();
        Map<Long, String> memberWhatsappById = new HashMap<>();
        if (!memberIds.isEmpty()) {
            for (TravelRequest tr : travelRequestRepo.findAllById(memberIds)) {
                if (tr == null || tr.getId() == null) continue;
                memberNameById.put(tr.getId(), tr.getName());
                memberWhatsappById.put(tr.getId(), tr.getPhone());
            }
        }

        // Clave para detectar pagos que provienen de opcionales Excursiones, incluso si el plan del miembro
        // ya existía (y por lo tanto no tiene notes marcadas).
        // Se arma un set con (groupId, memberId, paymentDate, amount).
        Set<String> excursionesOptionalKeys = new HashSet<>();
        try {
            if (!memberIds.isEmpty() && !groupIds.isEmpty()) {
                List<OptionalServicePaymentRecordRepository.OptionalServicePaymentKey> keys =
                        optionalServicePaymentRecordRepo.findKeysByServiceCodeAndMemberIdsAndGroupIds(
                                MemberOptionalServiceCode.EXCURSIONES,
                                new ArrayList<>(memberIds),
                                new ArrayList<>(groupIds)
                        );
                if (keys != null) {
                    for (var k : keys) {
                        if (k == null || k.getGroupId() == null || k.getMemberId() == null || k.getPaymentDate() == null || k.getAmount() == null)
                            continue;
                        excursionesOptionalKeys.add(buildExcKey(k.getGroupId(), k.getMemberId(), k.getPaymentDate(), k.getAmount()));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<ConciliationPaymentDto> out = new ArrayList<>(records.size());
        for (MemberPaymentRecord r : records) {
            if (r == null || r.getId() == null) continue;

            FinancialMovementConciliation c = conciliationByPaymentId.get(r.getId());

            String planType = null;
            Integer installmentsTotal = null;
            String paymentMethodUsed = null;
            try {
                if (r.getPlan() != null) {
                    if (r.getPlan().getPlanType() != null) {
                        planType = r.getPlan().getPlanType().name();
                    }

                    MemberPaymentPlan plan = planById.get(r.getPlan().getId());
                    if (plan != null) {
                        if (plan.getInstallments() != null) {
                            installmentsTotal = plan.getInstallments().size();
                        }
                        paymentMethodUsed = formatOneTimeMethod(plan.getOneTimeMethod());
                    }
                }
            } catch (Exception ignored) {
            }

            ConciliationStatus status = (c == null || c.getStatus() == null) ? ConciliationStatus.PENDING : c.getStatus();

            boolean hasReceipt = false;
            try {
                hasReceipt = (r.getReceiptFileName() != null && !r.getReceiptFileName().isBlank())
                        || (r.getReceiptContentType() != null && !r.getReceiptContentType().isBlank());
            } catch (Exception ignored) {
            }

            // Detalle/label en UI de conciliación: para pagos de opcionales Excursiones,
            // exponer el nombre con sufijo para poder identificar el origen del pago.
            String memberName = memberNameById.getOrDefault(r.getMemberId(), null);
            String whatsapp = memberWhatsappById.getOrDefault(r.getMemberId(), null);
            // Normalizar: algunos flujos históricos guardaban el nombre con prefijo "Pago:".
            // Para la UI de conciliación queremos mostrar solo el nombre.
            memberName = stripPagoPrefix(memberName);
            try {
                MemberPaymentPlan p = (r.getPlan() != null && r.getPlan().getId() != null)
                        ? planById.get(r.getPlan().getId())
                        : null;
                boolean isExc = false;
                if (p != null && isExcursionesOptionalPayment(p)) {
                    isExc = true;
                } else {
                    // Fallback: detectar por espejo contra pagos opcionales de Excursiones
                    if (r.getGroupId() != null && r.getMemberId() != null && r.getPaymentDate() != null && r.getAmount() != null) {
                        isExc = excursionesOptionalKeys.contains(buildExcKey(r.getGroupId(), r.getMemberId(), r.getPaymentDate(), r.getAmount()));
                    }
                }

                if (isExc) {
                    if (memberName != null && !memberName.isBlank()
                            && !memberName.toLowerCase(java.util.Locale.ROOT).contains("excursiones")) {
                        memberName = memberName.trim() + " | Excursiones";
                    }
                }
            } catch (Exception ignored) {
            }

            out.add(new ConciliationPaymentDto(
                    r.getId(),
                    r.getGroupId(),
                    r.getMemberId(),
                    memberName,
                    whatsapp,
                    planType,
                    r.getInstallmentNumber(),
                    installmentsTotal,
                    paymentMethodUsed,
                    r.getAmount(),
                    r.getCurrency(),
                    r.getPaymentDate(),
                    r.getReceiptLast4(),
                    hasReceipt,
                    c == null ? null : c.getBankReceiptNumber(),
                    r.getCreatedAt(),
                    status.name(),
                    c == null ? null : c.getNote(),
                    c == null ? null : c.getUpdatedAt()
            ));
        }

        return out;
    }

    private static String buildExcKey(Long groupId, Long memberId, java.time.LocalDate paymentDate, java.math.BigDecimal amount) {
        java.math.BigDecimal a = amount;
        try {
            a = a.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception ignored) {
        }
        return groupId + "|" + memberId + "|" + paymentDate + "|" + a;
    }

    @Transactional
    public void verifyPayment(Long paymentId, String bankReceiptNumber) {
        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }
        if (bankReceiptNumber == null || bankReceiptNumber.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bankReceiptNumber is required");
        }

        // Ensure payment exists
        paymentRecordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.MEMBER_PAYMENT_RECORD)
                    .movementId(paymentId)
                    .status(ConciliationStatus.VERIFIED)
                    .note(null)
                    .bankReceiptNumber(bankReceiptNumber.trim())
                    .build();
        } else {
            c.setStatus(ConciliationStatus.VERIFIED);
            c.setNote(null);
            c.setBankReceiptNumber(bankReceiptNumber.trim());
        }

        conciliationRepo.save(c);
    }


    @Transactional
    public void markPaymentPendingAccreditation(Long paymentId) {
        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }

        // Ensure payment exists
        paymentRecordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.MEMBER_PAYMENT_RECORD)
                    .movementId(paymentId)
                    .status(ConciliationStatus.PENDING_ACCREDITATION)
                    .note(null)
                    .bankReceiptNumber(null)
                    .build();
        } else {
            c.setStatus(ConciliationStatus.PENDING_ACCREDITATION);
            c.setNote(null);
            c.setBankReceiptNumber(null);
        }

        conciliationRepo.save(c);
    }

    @Transactional
    public void markPaymentProblem(Long paymentId, String note) {
        if (paymentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentId is required");
        }
        if (note == null || note.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note is required");
        }

        // Ensure payment exists
        paymentRecordRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.MEMBER_PAYMENT_RECORD)
                    .movementId(paymentId)
                    .status(ConciliationStatus.PROBLEM)
                    .note(note.trim())
                    .build();
        } else {
            c.setStatus(ConciliationStatus.PROBLEM);
            c.setNote(note.trim());
        }

        conciliationRepo.save(c);
    }

    @Transactional(readOnly = true)
    public boolean isPaymentVerified(Long paymentId) {
        if (paymentId == null) return false;
        return conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentId)
                .map(c -> c.getStatus() == ConciliationStatus.VERIFIED)
                .orElse(false);
    }

    @Transactional
    public void deleteConciliationIfExistsForPayment(Long paymentId) {
        if (paymentId == null) return;
        conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.MEMBER_PAYMENT_RECORD, paymentId)
                .ifPresent(conciliationRepo::delete);
    }
}
