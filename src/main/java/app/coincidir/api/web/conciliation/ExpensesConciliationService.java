package app.coincidir.api.web.conciliation;

import app.coincidir.api.domain.Prestador;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.web.conciliation.dto.ConciliationExpenseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExpensesConciliationService {

    private final ExpenseRepository expenseRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;
    private final MemberPaymentRecordRepository paymentRecordRepo;

    public record ReceiptDownload(byte[] bytes, String contentType, String fileName) {}

    @Transactional(readOnly = true)
    public List<ConciliationExpenseDto> listExpenses() {
        // Mantener un orden estable; el frontend re-ordena de todos modos.
        List<Expense> rows = expenseRepo.findAll(
                Sort.by(Sort.Order.asc("date"), Sort.Order.asc("id"))
        );
        if (rows == null || rows.isEmpty()) return List.of();

        List<Long> ids = rows.stream()
                .map(Expense::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, FinancialMovementConciliation> concByExpenseId = conciliationRepo
                .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.EXPENSE_RECORD, ids)
                .stream()
                .collect(Collectors.toMap(
                        FinancialMovementConciliation::getMovementId,
                        c -> c,
                        (a, b) -> a
                ));

        // Enriquecimiento: en algunos casos (gastos Proveedor/Normal) la info del grupo y el comprobante
        // puede venir de member_payment_record (mismo comprobante/últ.4).
        Set<String> last4ToLookup = new HashSet<>();
        for (Expense e : rows) {
            if (e == null || e.getId() == null) continue;
            FinancialMovementConciliation c = concByExpenseId.get(e.getId());
            String last4 = resolveLast4ForExpense(e, c);
            if (last4 != null) last4ToLookup.add(last4);
        }

        Map<String, List<MemberPaymentRecord>> payByLast4 = new HashMap<>();
        if (!last4ToLookup.isEmpty()) {
            List<MemberPaymentRecord> prs = paymentRecordRepo
                    .findAllByReceiptLast4InOrderByPaymentDateDescIdDesc(last4ToLookup);
            if (prs != null && !prs.isEmpty()) {
                payByLast4 = prs.stream()
                        .filter(r -> r != null && r.getReceiptLast4() != null)
                        .collect(Collectors.groupingBy(
                                MemberPaymentRecord::getReceiptLast4,
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
            }
        }

        List<ConciliationExpenseDto> out = new ArrayList<>(rows.size());
        for (Expense e : rows) {
            if (e == null || e.getId() == null) continue;

            FinancialMovementConciliation c = concByExpenseId.get(e.getId());
            ConciliationStatus status = (c == null || c.getStatus() == null) ? ConciliationStatus.PENDING : c.getStatus();

            Prestador p = null;
            try {
                p = e.getProvider();
            } catch (Exception ignored) {
            }

            Long providerId = null;
            String providerName = null;
            if (p != null) {
                try {
                    providerId = p.getId();
                } catch (Exception ignored) {
                }
                try {
                    providerName = p.getNombre();
                } catch (Exception ignored) {
                }
            }

            String receiptNumber = null;
            try {
                receiptNumber = e.getReceiptNumber();
            } catch (Exception ignored) {
            }

            String receiptLast4 = null;
            try {
                receiptLast4 = e.getReceiptLast4();
            } catch (Exception ignored) {
            }
            receiptLast4 = resolveLast4ForExpense(e, c);

            boolean hasReceipt = false;
            try {
                byte[] b = e.getReceiptBlob();
                hasReceipt = b != null && b.length > 0;
            } catch (Exception ignored) {
            }
            if (!hasReceipt) {
                try {
                    hasReceipt = (e.getReceiptFileName() != null && !e.getReceiptFileName().isBlank())
                            || (e.getReceiptContentType() != null && !e.getReceiptContentType().isBlank());
                } catch (Exception ignored) {
                }
            }

            Long groupId = null;
            try {
                groupId = e.getGroupId();
            } catch (Exception ignored) {
            }
            if (groupId == null) {
                groupId = resolveGroupIdFromText(safeGetNotes(e));
            }
            if (groupId == null) {
                groupId = resolveGroupIdFromText(safeGetConcept(e));
            }

            // Si faltan datos en expenses, intentamos resolverlos desde member_payment_record.
            MemberPaymentRecord best = pickBestPaymentRecord(
                    payByLast4.get(receiptLast4),
                    groupId,
                    e.getDate(),
                    e.getAmount()
            );
            if (best != null) {
                if (groupId == null) {
                    try {
                        groupId = best.getGroupId();
                    } catch (Exception ignored) {
                    }
                }
                if (receiptLast4 == null || receiptLast4.isBlank()) {
                    try {
                        receiptLast4 = best.getReceiptLast4();
                    } catch (Exception ignored) {
                    }
                }
                if (!hasReceipt) {
                    hasReceipt = hasReceipt(best);
                }
            }

            out.add(new ConciliationExpenseDto(
                    e.getId(),
                    groupId,
                    e.getDate(),
                    e.getType() == null ? null : e.getType().name(),
                    e.getCategory(),
                    e.getConcept(),
                    providerId,
                    providerName,
                    e.getPaymentMethod(),
                    e.getAmount(),
                    e.getCurrency(),
                    receiptLast4,
                    receiptNumber,
                    hasReceipt,
                    c == null ? null : c.getBankReceiptNumber(),
                    status.name(),
                    c == null ? null : c.getNote(),
                    c == null ? null : c.getUpdatedAt(),
                    // createdAt de expense es Instant; el DTO usa LocalDateTime solo para compat en UI.
                    // Convertimos best-effort a LocalDateTime si existe.
                    e.getCreatedAt() == null ? null : LocalDateTime.ofInstant(e.getCreatedAt(), java.time.ZoneId.systemDefault())
            ));
        }

        return out;
    }

    @Transactional(readOnly = true)
    public ReceiptDownload downloadReceipt(Long expenseId) {
        if (expenseId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expenseId is required");
        }

        Expense e = expenseRepo.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found: " + expenseId));

        byte[] bytes = null;
        try {
            bytes = e.getReceiptBlob();
        } catch (Exception ignored) {
        }

        if (bytes == null || bytes.length == 0) {
            // Fallback: algunos gastos guardan el comprobante en member_payment_record.
            String last4 = resolveLast4ForExpense(e, null);

            if (last4 != null) {
                List<MemberPaymentRecord> cands = paymentRecordRepo.findAllByReceiptLast4OrderByPaymentDateDescIdDesc(last4);
                MemberPaymentRecord best = pickBestPaymentRecord(cands, safeGetGroupId(e), e.getDate(), e.getAmount());
                ReceiptDownload pr = receiptFromPaymentRecord(best);
                if (pr != null) return pr;
            }

            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }

        String ct = null;
        try {
            ct = e.getReceiptContentType();
        } catch (Exception ignored) {
        }
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";

        String fn = null;
        try {
            fn = e.getReceiptFileName();
        } catch (Exception ignored) {
        }
        if (fn == null || fn.isBlank()) fn = "comprobante_gasto";

        return new ReceiptDownload(bytes, ct, fn);
    }

    // --------------------
    // Helpers de normalización / inferencia
    // --------------------

    private static final Pattern LAST_DIGITS = Pattern.compile("(\\d{4,})");
    private static final Pattern GROUP_ID = Pattern.compile("(?i)\\b(?:grupo|group|id_grupo|id\\s*grupo)\\s*[:#-]?\\s*(\\d{1,10})\\b");

    /**
     * Resuelve "últimos 4" del comprobante para un gasto.
     * Prioridad: receipt_last4 -> receipt_number -> notes/concept -> bankReceiptNumber (conciliación).
     */
    private static String resolveLast4ForExpense(Expense e, FinancialMovementConciliation c) {
        String last4 = safeTrim(safeGetReceiptLast4(e));
        if (last4 != null) {
            return last4.length() > 4 ? last4.substring(last4.length() - 4) : last4;
        }

        last4 = safeTrim(deriveLast4FromNumber(safeGetReceiptNumber(e)));
        if (last4 != null) return last4;

        last4 = extractLast4FromText(safeGetNotes(e));
        if (last4 != null) return last4;

        last4 = extractLast4FromText(safeGetConcept(e));
        if (last4 != null) return last4;

        if (c != null) {
            try {
                last4 = extractLast4FromText(c.getBankReceiptNumber());
            } catch (Exception ignored) {
            }
        }

        return last4;
    }

    private static String extractLast4FromText(String text) {
        String t = safeTrim(text);
        if (t == null) return null;
        Matcher m = LAST_DIGITS.matcher(t);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last == null) return null;
        return last.length() > 4 ? last.substring(last.length() - 4) : last;
    }

    private static Long resolveGroupIdFromText(String text) {
        String t = safeTrim(text);
        if (t == null) return null;
        try {
            Matcher m = GROUP_ID.matcher(t);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String safeTrim(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isBlank() ? null : t;
    }

    private static String deriveLast4FromNumber(String receiptNumber) {
        if (receiptNumber == null) return null;
        String t = receiptNumber.trim();
        if (t.isBlank()) return null;
        return (t.length() > 4) ? t.substring(t.length() - 4) : t;
    }

    private static String safeGetReceiptNumber(Expense e) {
        try {
            return e == null ? null : e.getReceiptNumber();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeGetReceiptLast4(Expense e) {
        try {
            return e == null ? null : e.getReceiptLast4();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeGetNotes(Expense e) {
        try {
            return e == null ? null : e.getNotes();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeGetConcept(Expense e) {
        try {
            return e == null ? null : e.getConcept();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long safeGetGroupId(Expense e) {
        try {
            return e == null ? null : e.getGroupId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasReceipt(MemberPaymentRecord r) {
        if (r == null) return false;
        try {
            byte[] b = r.getReceiptBlob();
            if (b != null && b.length > 0) return true;
        } catch (Exception ignored) {
        }
        try {
            if (r.getReceiptFileName() != null && !r.getReceiptFileName().isBlank()) return true;
            if (r.getReceiptContentType() != null && !r.getReceiptContentType().isBlank()) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static ReceiptDownload receiptFromPaymentRecord(MemberPaymentRecord r) {
        if (r == null) return null;
        byte[] bytes;
        try {
            bytes = r.getReceiptBlob();
        } catch (Exception ignored) {
            return null;
        }
        if (bytes == null || bytes.length == 0) return null;

        String ct = null;
        try {
            ct = r.getReceiptContentType();
        } catch (Exception ignored) {
        }
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";

        String fn = null;
        try {
            fn = r.getReceiptFileName();
        } catch (Exception ignored) {
        }
        if (fn == null || fn.isBlank()) fn = "comprobante_pago";

        return new ReceiptDownload(bytes, ct, fn);
    }

    private static MemberPaymentRecord pickBestPaymentRecord(
            List<MemberPaymentRecord> candidates,
            Long groupId,
            java.time.LocalDate date,
            java.math.BigDecimal amount
    ) {
        if (candidates == null || candidates.isEmpty()) return null;

        List<MemberPaymentRecord> filtered = candidates;

        if (groupId != null) {
            List<MemberPaymentRecord> byGroup = candidates.stream()
                    .filter(r -> {
                        try {
                            return r != null && Objects.equals(r.getGroupId(), groupId);
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (!byGroup.isEmpty()) filtered = byGroup;
        }

        if (date != null) {
            List<MemberPaymentRecord> byDate = filtered.stream()
                    .filter(r -> {
                        try {
                            return r != null && Objects.equals(r.getPaymentDate(), date);
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (!byDate.isEmpty()) filtered = byDate;
        }

        if (amount != null) {
            java.math.BigDecimal a = amount;
            List<MemberPaymentRecord> byAmount = filtered.stream()
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

    @Transactional
    public void verifyExpense(Long expenseId, String bankReceiptNumber) {
        if (expenseId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expenseId is required");
        }
        if (bankReceiptNumber == null || bankReceiptNumber.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bankReceiptNumber is required");
        }

        // Ensure expense exists
        expenseRepo.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found: " + expenseId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.EXPENSE_RECORD, expenseId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.EXPENSE_RECORD)
                    .movementId(expenseId)
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
    public void markExpensePendingAccreditation(Long expenseId) {
        if (expenseId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expenseId is required");
        }

        // Ensure expense exists
        expenseRepo.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found: " + expenseId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.EXPENSE_RECORD, expenseId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.EXPENSE_RECORD)
                    .movementId(expenseId)
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
    public void markExpenseProblem(Long expenseId, String note) {
        if (expenseId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expenseId is required");
        }
        if (note == null || note.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note is required");
        }

        // Ensure expense exists
        expenseRepo.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found: " + expenseId));

        FinancialMovementConciliation c = conciliationRepo
                .findByMovementTypeAndMovementId(FinancialMovementType.EXPENSE_RECORD, expenseId)
                .orElse(null);

        if (c == null) {
            c = FinancialMovementConciliation.builder()
                    .movementType(FinancialMovementType.EXPENSE_RECORD)
                    .movementId(expenseId)
                    .status(ConciliationStatus.PROBLEM)
                    .note(note.trim())
                    .build();
        } else {
            c.setStatus(ConciliationStatus.PROBLEM);
            c.setNote(note.trim());
        }

        conciliationRepo.save(c);
    }
}
