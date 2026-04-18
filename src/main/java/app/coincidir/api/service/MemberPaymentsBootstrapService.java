package app.coincidir.api.service;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.payment.*;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.domain.TravelGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crea (si corresponde) el plan + cuotas + registro de pago para miembros que llegan al grupo
 * con datos de seña (deposit_*) en travel_request.
 *
 * Motivo: la carga manual crea travel_request sin group_id al inicio, por eso guarda el pago
 * en deposit_*. Cuando el matching asigna el request a un grupo, se materializa en las tablas:
 * member_payment_plan / member_payment_installment / member_payment_record.
 */
@Service
@RequiredArgsConstructor
public class MemberPaymentsBootstrapService {

    private final MemberPaymentPlanRepository planRepo;
    private final MemberPaymentRecordRepository recordRepo;
    private final TravelGroupRepository groupRepo;
    private final ObjectMapper objectMapper;

    private static final String TEMP_GROUP_DEST = "__TEMP_PAYMENTS__";

    private static final Pattern LAST4_PATTERN = Pattern.compile("(?i)\"?(receiptLast4|last4)\"?\\s*[:=]\\s*\"?([0-9]{4})\"?");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?i)\"?method\"?\\s*[:=]\\s*\"?([A-Z_]+)\"?");
    private static final Pattern PLANTYPE_PATTERN = Pattern.compile("(?i)\"?planType\"?\\s*[:=]\\s*\"?([A-Z_]+)\"?");

    @Transactional
    public void bootstrapFromRequestIfNeeded(TravelRequest r) {
        if (r == null) return;
        if (r.getGroup() == null || r.getGroup().getId() == null) return;
        if (r.getId() == null) return;

        Long groupId = r.getGroup().getId();
        Long memberId = r.getId();

        // Caso "Carga manual": el pago puede haberse materializado antes de asignar group_id
        // (plan/records con group_id NULL o en __TEMP_PAYMENTS__). En ese caso, migramos al group_id real y salimos.
        try {
            // 0) Prioridad: si existe un plan en el grupo temporal, migrarlo al grupo real.
            List<Long> tempGroupIds = findExistingTempGroupIds();
            if (tempGroupIds != null && !tempGroupIds.isEmpty()) {
                for (Long tempGroupId : tempGroupIds) {
                    if (tempGroupId == null) continue;
                    var tempPlanOpt = planRepo.findByGroupIdAndMemberId(tempGroupId, memberId);
                    if (tempPlanOpt.isPresent()) {
                        // Si ya existe plan en el grupo, no migrar para evitar violar UNIQUE(group_id, member_id).
                        if (planRepo.findByGroupIdAndMemberId(groupId, memberId).isEmpty()) {
                            MemberPaymentPlan tempPlan = tempPlanOpt.get();
                            tempPlan.setGroupId(groupId);
                            planRepo.save(tempPlan);

                            // Migrar registros de pago desde el temp group
                            var recsTemp = recordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(tempGroupId, memberId);
                            if (recsTemp != null && !recsTemp.isEmpty()) {
                                for (MemberPaymentRecord pr : recsTemp) {
                                    if (pr == null) continue;
                                    pr.setGroupId(groupId);
                                }
                                recordRepo.saveAll(recsTemp);
                            }

                            // Migrar también registros históricos con group_id NULL (por compat)
                            var recsNull = recordRepo.findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(memberId);
                            if (recsNull != null && !recsNull.isEmpty()) {
                                for (MemberPaymentRecord pr : recsNull) {
                                    if (pr == null) continue;
                                    pr.setGroupId(groupId);
                                }
                                recordRepo.saveAll(recsNull);
                            }
                        }
                        return;
                    }
                }
            }

            var ungroupedOpt = planRepo.findUngroupedWithInstallments(memberId);
            if (ungroupedOpt.isPresent()) {
                // Si ya existe plan en el grupo, no forzar migración para evitar violar UNIQUE(group_id, member_id).
                if (planRepo.findByGroupIdAndMemberId(groupId, memberId).isEmpty()) {
                    MemberPaymentPlan ungrouped = ungroupedOpt.get();
                    ungrouped.setGroupId(groupId);
                    planRepo.save(ungrouped);

                    // Migrar registros de pago
                    var recs = recordRepo.findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(memberId);
                    if (recs != null && !recs.isEmpty()) {
                        for (MemberPaymentRecord pr : recs) {
                            if (pr == null) continue;
                            pr.setGroupId(groupId);
                        }
                        recordRepo.saveAll(recs);
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // Ya existe plan -> no tocar
        if (planRepo.findByGroupIdAndMemberId(groupId, memberId).isPresent()) {
            return;
        }

        BigDecimal totalAmount = r.getDepositAmount();
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        LocalDate paymentDate = r.getDepositDate();
        if (paymentDate == null) {
            return;
        }

        PaymentOneTimeMethod method = parseMethod(r);
        if (method == null) {
            return;
        }

        String notes = r.getDepositNotes();
        JsonNode meta = tryReadMeta(notes);

        PaymentPlanType planType = parsePlanType(notes, meta);
        String last4 = parseLast4(notes, meta);
        if (last4 == null) {
            return;
        }

        if (planType == PaymentPlanType.OWN_FINANCING) {
            List<InstallmentMeta> installments = parseInstallments(meta);
            // Si no hay cuotas en notas, no podemos materializar la financiación.
            if (installments.isEmpty()) {
                return;
            }

            installments.sort(Comparator.comparingInt(x -> x.installmentNumber));

            // Validaciones mínimas para evitar insertar cuotas inválidas
            for (InstallmentMeta im : installments) {
                if (im == null) return;
                if (im.amount == null || im.amount.compareTo(BigDecimal.ZERO) <= 0) return;
                if (im.installmentNumber != 1 && im.dueDate == null) return;
            }

            MemberPaymentPlan plan = MemberPaymentPlan.builder()
                    .groupId(groupId)
                    .memberId(memberId)
                    .planType(PaymentPlanType.OWN_FINANCING)
                    .oneTimeMethod(method)
                    .totalAmount(totalAmount)
                    .currency("ARS")
                    .notes(notes)
                    .receiptLast4(last4)
                    .build();

            for (InstallmentMeta im : installments) {
                int n = Math.max(1, im.installmentNumber);
                BigDecimal amt = im.amount;
                LocalDate due = im.dueDate;
                if (n == 1) {
                    // Regla: cuota 1 usa la fecha de pago (depositDate)
                    due = paymentDate;
                }
                MemberPaymentInstallment inst = MemberPaymentInstallment.builder()
                        .plan(plan)
                        .installmentNumber(n)
                        .dueDate(due)
                        .amount(amt)
                        .status(n == 1 ? InstallmentStatus.PAID : InstallmentStatus.PLANNED)
                        .paidDate(n == 1 ? paymentDate : null)
                        .build();
                plan.getInstallments().add(inst);
            }

            MemberPaymentPlan savedPlan = planRepo.save(plan);

            // Registro del pago de la cuota 1
            InstallmentMeta firstMeta = installments.stream().filter(x -> x != null && x.installmentNumber == 1).findFirst().orElse(installments.get(0));
            BigDecimal firstAmount = firstMeta.amount != null ? firstMeta.amount : totalAmount;
            if (!recordRepo.existsByPlanIdAndInstallmentNumber(savedPlan.getId(), 1)) {
                MemberPaymentRecord rec = MemberPaymentRecord.builder()
                        .groupId(groupId)
                        .memberId(memberId)
                        .plan(savedPlan)
                        .installmentNumber(1)
                        .amount(firstAmount)
                        .paymentDate(paymentDate)
                        .receiptLast4(last4)
                        .currency("ARS")
                        .build();
                copyReceiptFromTravelRequest(r, rec);
                recordRepo.save(rec);
            }
            return;
        }

        // Default: ONE_TIME
        MemberPaymentPlan plan = MemberPaymentPlan.builder()
                .groupId(groupId)
                .memberId(memberId)
                .planType(PaymentPlanType.ONE_TIME)
                .oneTimeMethod(method)
                .totalAmount(totalAmount)
                .currency("ARS")
                .notes(notes)
                .receiptLast4(last4)
                .build();

        MemberPaymentInstallment inst = MemberPaymentInstallment.builder()
                .plan(plan)
                .installmentNumber(1)
                .dueDate(paymentDate)
                .amount(totalAmount)
                .status(InstallmentStatus.PAID)
                .paidDate(paymentDate)
                .build();
        plan.getInstallments().add(inst);

        MemberPaymentPlan savedPlan = planRepo.save(plan);

        if (!recordRepo.existsByPlanIdAndInstallmentNumber(savedPlan.getId(), 1)) {
            MemberPaymentRecord rec = MemberPaymentRecord.builder()
                    .groupId(groupId)
                    .memberId(memberId)
                    .plan(savedPlan)
                    .installmentNumber(1)
                    .amount(totalAmount)
                    .paymentDate(paymentDate)
                    .receiptLast4(last4)
                    .currency("ARS")
                    .build();
            copyReceiptFromTravelRequest(r, rec);
            recordRepo.save(rec);
        }
    }

    private List<Long> findExistingTempGroupIds() {
        try {
            List<TravelGroup> existing = groupRepo.findAllByDestinationOrdered(TEMP_GROUP_DEST);
            if (existing == null || existing.isEmpty()) return List.of();
            return existing.stream()
                    .filter(Objects::nonNull)
                    .map(TravelGroup::getId)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static PaymentOneTimeMethod parseMethod(TravelRequest r) {
        if (r == null) return null;
        String raw = r.getDepositPaymentMethod();
        if (raw != null) {
            raw = raw.trim();
            if (!raw.isEmpty()) {
                // legacy: "ONE_TIME:TRANSFERENCIA"
                int idx = raw.indexOf(':');
                if (idx >= 0 && idx + 1 < raw.length()) {
                    raw = raw.substring(idx + 1);
                }
                String normalized = raw.trim().toUpperCase(Locale.ROOT);
                try {
                    return PaymentOneTimeMethod.valueOf(normalized);
                } catch (Exception ignored) {
                    // fallback: intentar desde notes
                }
            }
        }

        String notes = r.getDepositNotes();
        if (notes != null) {
            // soporta map.toString() / JSON
            Matcher m = METHOD_PATTERN.matcher(notes);
            if (m.find()) {
                String normalized = m.group(1).trim().toUpperCase(Locale.ROOT);
                try {
                    return PaymentOneTimeMethod.valueOf(normalized);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private JsonNode tryReadMeta(String notes) {
        if (notes == null) return null;
        String t = notes.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return null;
        try {
            JsonNode node = objectMapper.readTree(t);
            if (node != null && node.isObject()) return node;
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static PaymentPlanType parsePlanType(String notes, JsonNode meta) {
        // JSON
        if (meta != null) {
            String raw = text(meta, "planType");
            if (raw != null) {
                try {
                    return PaymentPlanType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        // map.toString() / texto
        if (notes != null) {
            Matcher m = PLANTYPE_PATTERN.matcher(notes);
            if (m.find()) {
                try {
                    return PaymentPlanType.valueOf(m.group(1).trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return PaymentPlanType.ONE_TIME;
    }

    private static String parseLast4(String notes, JsonNode meta) {
        // JSON
        if (meta != null) {
            String v = text(meta, "receiptLast4");
            if (v == null) v = text(meta, "last4");
            if (v != null) {
                String t = v.replaceAll("\\D", "").trim();
                if (t.matches("^[0-9]{4}$")) return t;
            }
        }

        if (notes == null) return null;
        String t = notes.trim();
        if (t.matches("^[0-9]{4}$")) return t;
        Matcher m = LAST4_PATTERN.matcher(t);
        if (m.find()) return m.group(2);
        return null;
    }

    private static List<InstallmentMeta> parseInstallments(JsonNode meta) {
        List<InstallmentMeta> out = new ArrayList<>();
        if (meta == null) return out;
        JsonNode arr = meta.get("installments");
        if (arr == null || !arr.isArray()) return out;

        for (int i = 0; i < arr.size(); i++) {
            JsonNode it = arr.get(i);
            if (it == null || !it.isObject()) continue;
            int n = it.has("installmentNumber") ? it.path("installmentNumber").asInt(i + 1) : (i + 1);
            BigDecimal amount = parseBigDecimal(it.get("amount"));
            LocalDate due = parseLocalDate(it.get("dueDate"));
            out.add(new InstallmentMeta(n, amount, due));
        }
        return out;
    }

    private static void copyReceiptFromTravelRequest(TravelRequest r, MemberPaymentRecord rec) {
        if (r == null || rec == null) return;
        byte[] blob = r.getDepositReceiptBlob();
        if (blob == null || blob.length == 0) return;
        rec.setReceiptBlob(blob);
        rec.setReceiptContentType(r.getDepositReceiptContentType());
        rec.setReceiptFileName(r.getDepositReceiptFileName());
    }

    private static String text(JsonNode obj, String field) {
        if (obj == null || field == null) return null;
        JsonNode v = obj.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        return v.asText(null);
    }

    private static BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (node.isNumber()) {
                // usar texto para evitar issues de double
                return new BigDecimal(node.asText());
            }
            String t = node.asText(null);
            if (t == null) return null;
            t = t.trim().replace(",", ".");
            if (t.isEmpty()) return null;
            return new BigDecimal(t);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDate parseLocalDate(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            String t = node.asText(null);
            if (t == null) return null;
            t = t.trim();
            if (t.isEmpty()) return null;
            return LocalDate.parse(t);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class InstallmentMeta {
        final int installmentNumber;
        final BigDecimal amount;
        final LocalDate dueDate;

        private InstallmentMeta(int installmentNumber, BigDecimal amount, LocalDate dueDate) {
            this.installmentNumber = installmentNumber;
            this.amount = amount;
            this.dueDate = dueDate;
        }
    }
}
