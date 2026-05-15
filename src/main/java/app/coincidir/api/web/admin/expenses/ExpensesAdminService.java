package app.coincidir.api.web.admin.expenses;

import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.Prestador;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.expense.ExpenseStatus;
import app.coincidir.api.domain.expense.ExpenseType;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.PrestadorRepository;
import app.coincidir.api.web.admin.expenses.dto.ExpenseDto;
import app.coincidir.api.web.admin.expenses.dto.ExpenseUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ExpensesAdminService {

    private final ExpenseRepository expenseRepo;
    private final MemberPaymentRecordRepository memberPaymentRecordRepo;
    private final PrestadorRepository prestadorRepo;

    public Page<ExpenseDto> list(
            String q,
            LocalDate from,
            LocalDate to,
            String type,
            String category,
            Long providerId,
            String paymentMethod,
            String status,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Pageable pageable
    ) {
        Specification<Expense> spec = buildSpec(q, from, to, type, category, providerId, paymentMethod, status, minAmount, maxAmount);
        return expenseRepo.findAll(spec, pageable).map(this::toDto);
    }

    public ExpenseDto create(ExpenseUpsertRequest req) {
        Expense e = new Expense();
        apply(e, req);
        return toDto(expenseRepo.save(e));
    }

    public ExpenseDto update(long id, ExpenseUpsertRequest req) {
        Expense e = expenseRepo.findById(id).orElseThrow(() -> new NotFoundException("Gasto no encontrado"));
        apply(e, req);
        return toDto(expenseRepo.save(e));
    }

    @Transactional
    public ExpenseDto uploadReceipt(long expenseId, MultipartFile file) {
        Expense e = expenseRepo.findById(expenseId).orElseThrow(() -> new NotFoundException("Gasto no encontrado"));

        if (file == null || file.isEmpty()) {
            return toDto(e);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception ex) {
            throw new BadRequestException("No se pudo leer el archivo adjunto");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("El archivo adjunto está vacío");
        }

        String ct = file.getContentType();
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
        String fn = file.getOriginalFilename();
        if (fn == null || fn.isBlank()) fn = "comprobante";

        e.setReceiptBlob(bytes);
        e.setReceiptContentType(ct);
        e.setReceiptFileName(fn);

        // Asegurar receipt_last4 para conciliación (si no viene)
        if (e.getReceiptLast4() == null || e.getReceiptLast4().isBlank()) {
            String last4 = firstNonBlank(
                    extractLast4Digits(e.getReceiptNumber()),
                    extractLast4Digits(e.getNotes()),
                    extractLast4Digits(e.getConcept())
            );
            if (last4 != null) {
                e.setReceiptLast4(last4);
            }
        }

        Expense saved = expenseRepo.save(e);

        // También persistimos el comprobante en member_payment_record (si existe uno que coincida)
        try {
            String last4 = saved.getReceiptLast4();
            if (last4 == null || last4.isBlank()) {
                last4 = extractLast4Digits(saved.getReceiptNumber());
            }
            if (last4 != null && !last4.isBlank()) {
                List<MemberPaymentRecord> candidates = memberPaymentRecordRepo.findAllByReceiptLast4OrderByPaymentDateDescIdDesc(last4);
                MemberPaymentRecord best = pickBestPaymentRecord(candidates, saved.getGroupId(), saved.getDate(), saved.getAmount());
                if (best != null) {
                    byte[] existing = best.getReceiptBlob();
                    if (existing == null || existing.length == 0) {
                        best.setReceiptBlob(bytes);
                        best.setReceiptContentType(ct);
                        best.setReceiptFileName(fn);
                        memberPaymentRecordRepo.save(best);
                    }
                }
            }
        } catch (Exception ignored) {
            // no romper la carga del gasto si no se puede reflejar en member_payment_record
        }

        return toDto(saved);
    }

    public void delete(long id) {
        Expense e = expenseRepo.findById(id).orElseThrow(() -> new NotFoundException("Gasto no encontrado"));
        expenseRepo.delete(e);
    }

    private void apply(Expense e, ExpenseUpsertRequest req) {
        if (req == null) throw new BadRequestException("Body requerido");
        if (req.date() == null) throw new BadRequestException("La fecha es obligatoria");
        if (req.concept() == null || req.concept().isBlank()) throw new BadRequestException("El concepto es obligatorio");
        if (req.amount() == null) throw new BadRequestException("El monto es obligatorio");
        if (req.amount().compareTo(BigDecimal.ZERO) <= 0) throw new BadRequestException("El monto debe ser mayor a 0");

        if (req.type() == null || req.type().isBlank()) {
            throw new BadRequestException("El tipo es obligatorio");
        }
        ExpenseType et = parseEnum(req.type(), ExpenseType.class, "type");
        ExpenseStatus es = req.status() == null || req.status().isBlank() ? null : parseEnum(req.status(), ExpenseStatus.class, "status");

        if (et == ExpenseType.PROVEEDOR && req.providerId() == null) {
            throw new BadRequestException("Para gastos a proveedores, el proveedor es obligatorio");
        }

        Prestador provider = null;
        if (req.providerId() != null) {
            provider = prestadorRepo.findById(req.providerId())
                    .orElseThrow(() -> new BadRequestException("Proveedor inexistente"));
        }

        e.setDate(req.date());
        e.setType(et);
        e.setCategory(trimToNull(req.category()));
        e.setConcept(req.concept().trim());
        e.setProvider(provider);
        e.setPaymentMethod(trimToNull(req.paymentMethod()));
        e.setAmount(req.amount());
        e.setCurrency((req.currency() == null || req.currency().isBlank()) ? "ARS" : req.currency().trim().toUpperCase(Locale.ROOT));
        e.setStatus(es);
        String rn = trimToNull(req.receiptNumber());
        e.setReceiptNumber(rn);
        e.setReceiptLast4(extractLast4Digits(rn));
        e.setNotes(trimToNull(req.notes()));
    }

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String extractLast4Digits(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D+", "");
        if (digits.length() < 4) return null;
        return digits.substring(digits.length() - 4);
    }

    private static MemberPaymentRecord pickBestPaymentRecord(
            java.util.List<MemberPaymentRecord> candidates,
            Long groupId,
            java.time.LocalDate date,
            java.math.BigDecimal amount
    ) {
        if (candidates == null || candidates.isEmpty()) return null;

        java.util.List<MemberPaymentRecord> filtered = candidates;

        if (groupId != null) {
            java.util.List<MemberPaymentRecord> byGroup = candidates.stream()
                    .filter(r -> {
                        try {
                            return r != null && java.util.Objects.equals(r.getGroupId(), groupId);
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (!byGroup.isEmpty()) filtered = byGroup;
        }

        if (date != null) {
            java.util.List<MemberPaymentRecord> byDate = filtered.stream()
                    .filter(r -> {
                        try {
                            return r != null && java.util.Objects.equals(r.getPaymentDate(), date);
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (!byDate.isEmpty()) filtered = byDate;
        }

        if (amount != null) {
            java.math.BigDecimal a = amount;
            java.util.List<MemberPaymentRecord> byAmount = filtered.stream()
                    .filter(r -> {
                        try {
                            return r != null && r.getAmount() != null && r.getAmount().compareTo(a) == 0;
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (!byAmount.isEmpty()) return byAmount.get(0);
        }

        return filtered.get(0);
    }

    private ExpenseDto toDto(Expense e) {
        Long providerId = e.getProvider() == null ? null : e.getProvider().getId();
        String providerName = e.getProvider() == null ? null : e.getProvider().getNombre();

        Boolean hasReceipt = resolveHasReceipt(e);

        return new ExpenseDto(
                e.getId(),
                e.getDate(),
                e.getType() == null ? null : e.getType().name(),
                e.getCategory(),
                e.getConcept(),
                providerId,
                providerName,
                e.getPaymentMethod(),
                e.getAmount(),
                e.getCurrency(),
                e.getStatus() == null ? null : e.getStatus().name(),
                e.getReceiptNumber(),
                e.getReceiptLast4(),
                hasReceipt,
                e.getNotes(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private Boolean resolveHasReceipt(Expense e) {
        if (e == null) return false;

        try {
            byte[] b = e.getReceiptBlob();
            if (b != null && b.length > 0) return true;
        } catch (Exception ignored) {
        }

        // Fallback: algunos gastos (operaciones/conciliación) guardan el comprobante en member_payment_record.
        String last4 = firstNonBlank(
                safeTrim(e.getReceiptLast4()),
                extractLast4Digits(e.getReceiptNumber()),
                extractLast4Digits(e.getNotes()),
                extractLast4Digits(e.getConcept())
        );
        if (last4 == null) return false;
        last4 = last4.length() > 4 ? last4.substring(last4.length() - 4) : last4;

        try {
            return memberPaymentRecordRepo.existsWithReceiptByLast4(last4);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeTrim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private Specification<Expense> buildSpec(
            String q,
            LocalDate from,
            LocalDate to,
            String type,
            String category,
            Long providerId,
            String paymentMethod,
            String status,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        return (root, query, cb) -> {
            Predicate p = cb.conjunction();

            if (from != null) p = cb.and(p, cb.greaterThanOrEqualTo(root.get("date"), from));
            if (to != null) p = cb.and(p, cb.lessThanOrEqualTo(root.get("date"), to));

            if (type != null && !type.isBlank()) {
                ExpenseType et = parseEnum(type, ExpenseType.class, "type");
                p = cb.and(p, cb.equal(root.get("type"), et));
            }

            if (status != null && !status.isBlank()) {
                ExpenseStatus es = parseEnum(status, ExpenseStatus.class, "status");
                p = cb.and(p, cb.equal(root.get("status"), es));
            }

            if (category != null && !category.isBlank()) {
                String term = "%" + category.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.like(cb.lower(root.get("category")), term));
            }

            if (providerId != null) {
                p = cb.and(p, cb.equal(root.get("provider").get("id"), providerId));
            }

            if (paymentMethod != null && !paymentMethod.isBlank()) {
                p = cb.and(p, cb.equal(cb.lower(root.get("paymentMethod")), paymentMethod.trim().toLowerCase(Locale.ROOT)));
            }

            if (minAmount != null) p = cb.and(p, cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
            if (maxAmount != null) p = cb.and(p, cb.lessThanOrEqualTo(root.get("amount"), maxAmount));

            if (q != null && !q.isBlank()) {
                String term = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                Join<Expense, Prestador> providerJoin = root.join("provider", JoinType.LEFT);

                // category puede ser null, usamos coalesce
                Predicate pConcept = cb.like(cb.lower(root.get("concept")), term);
                Predicate pCat = cb.like(cb.lower(cb.coalesce(root.get("category"), "")), term);
                Predicate pRec = cb.like(cb.lower(cb.coalesce(root.get("receiptNumber"), "")), term);
                Predicate pNotes = cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), term);
                Predicate pProv = cb.like(cb.lower(cb.coalesce(providerJoin.get("nombre"), "")), term);

                p = cb.and(p, cb.or(pConcept, pCat, pRec, pNotes, pProv));
            }

            return p;
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String field) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isBlank()) return null;
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new BadRequestException("Valor inválido para " + field + ": " + value);
        }
    }
}
