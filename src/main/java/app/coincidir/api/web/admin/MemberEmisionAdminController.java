package app.coincidir.api.web.admin;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.MemberEmision;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.TravelRequestAirService;
import app.coincidir.api.domain.TravelDestination;
import app.coincidir.api.domain.MemberAirService;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.MemberAirServiceRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.MemberEmisionRepository;
import app.coincidir.api.repository.TravelDestinationRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.TravelRequestAirServiceRepository;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.web.admin.dto.EmisionTravelDateOptionDto;
import app.coincidir.api.web.admin.dto.MemberEmisionPendingDto;
import app.coincidir.api.web.admin.dto.MemberEmisionUpdateRequest;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/emisiones")
@RequiredArgsConstructor
public class MemberEmisionAdminController {

    private final EntityManager entityManager;

    private final MemberEmisionRepository memberEmisionRepo;
    private final TravelRequestAirServiceRepository requestAirRepo;
    private final TravelRequestRepository travelRequestRepo;
    private final ExpenseRepository expenseRepo;
    private final MemberPaymentRecordRepository memberPaymentRecordRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;

    private final GroupServiceMenuItemRepository groupMenuItemRepo;
    private final GroupAirServiceRepository groupAirServiceRepo;

    private final MemberAirServiceRepository memberAirServiceRepo;
    private final TravelDestinationRepository destinationRepo;


    /**
     * Devuelve todas las emisiones (pendientes y emitidas).
     */
    @GetMapping
    public List<MemberEmisionPendingDto> listAll() {
        // Regla: para que figure como "pendiente" en Operaciones, el pago asociado debe estar conciliado (VERIFIED).
        // Emisiones ya emitidas siempre se devuelven.
        return memberEmisionRepo.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(e -> Boolean.TRUE.equals(e.getEmitted()) || isAirPaymentConciliatedForRequest(e.getRequestId()))
                .map(this::toDto)
                .toList();
    }

    /**
     * Devuelve los pasajeros cargados por "Carga manual de pasajero" que todavía no fueron emitidos.
     */
    @GetMapping("/pending")
    public List<MemberEmisionPendingDto> listPending() {
        // Solo pendientes con pago conciliado.
        return memberEmisionRepo.findByEmittedFalseOrEmittedIsNullOrderByCreatedAtDesc()
                .stream()
                .filter(e -> isAirPaymentConciliatedForRequest(e.getRequestId()))
                .map(this::toDto)
                .toList();
    }

    private boolean isAirPaymentConciliatedForRequest(Long requestId) {
        if (requestId == null) return false;

        // 1) Preferir conciliación de pagos de pasajeros (Check Panel) -> MEMBER_PAYMENT_RECORD.
        //    En el panel de Operaciones esto representa "Pagos confirmados".
        TravelRequest tr = null;
        Long groupId = null;
        try {
            tr = travelRequestRepo.findById(requestId).orElse(null);
        } catch (Exception ignored) {
        }
        if (tr != null && tr.getGroup() != null) {
            try {
                groupId = tr.getGroup().getId();
            } catch (Exception ignored) {
            }
        }

        if (isMemberPaymentsConciliated(requestId, groupId)) {
            return true;
        }

        // 2) Fallback legacy: conciliación de egresos/pagos a proveedor -> EXPENSE_RECORD.
        //    Se intenta detectar gastos que referencien al pasajero por notes.
        String token = "pasajeroId=" + requestId;
        List<Expense> expenses;
        try {
            if (groupId != null) {
                expenses = expenseRepo.findAllByGroupIdAndNotesContaining(groupId, token);
            } else {
                expenses = expenseRepo.findAllByNotesContaining(token);
            }
        } catch (Exception ignored) {
            expenses = null;
        }
        if (expenses == null || expenses.isEmpty()) return false;

        List<Long> ids = expenses.stream()
                .filter(e -> e != null && e.getId() != null)
                .map(Expense::getId)
                .distinct()
                .toList();
        if (ids.isEmpty()) return false;

        // Requiere conciliación VERIFIED para todos los gastos asociados.
        List<FinancialMovementConciliation> conc = conciliationRepo
                .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.EXPENSE_RECORD, ids);

        java.util.Map<Long, ConciliationStatus> byId = (conc == null ? java.util.List.<FinancialMovementConciliation>of() : conc)
                .stream()
                .filter(c -> c != null && c.getMovementId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        FinancialMovementConciliation::getMovementId,
                        FinancialMovementConciliation::getStatus,
                        (a, b) -> a
                ));

        for (Long id : ids) {
            if (id == null) continue;
            ConciliationStatus st = byId.get(id);
            if (st != ConciliationStatus.VERIFIED) return false;
        }
        return true;
    }

    private boolean isMemberPaymentsConciliated(Long memberId, Long groupId) {
        if (memberId == null) return false;

        java.util.List<app.coincidir.api.domain.payment.MemberPaymentRecord> records;
        try {
            if (groupId != null) {
                records = memberPaymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId);
            } else {
                records = memberPaymentRecordRepo.findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(memberId);
            }
        } catch (Exception ignored) {
            records = null;
        }
        if (records == null || records.isEmpty()) return false;

        java.util.List<Long> ids = records.stream()
                .filter(r -> r != null && r.getId() != null)
                .map(app.coincidir.api.domain.payment.MemberPaymentRecord::getId)
                .distinct()
                .toList();
        if (ids.isEmpty()) return false;

        java.util.List<FinancialMovementConciliation> conc;
        try {
            conc = conciliationRepo.findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, ids);
        } catch (Exception ignored) {
            conc = null;
        }

        java.util.Map<Long, ConciliationStatus> byId = (conc == null ? java.util.List.<FinancialMovementConciliation>of() : conc)
                .stream()
                .filter(c -> c != null && c.getMovementId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        FinancialMovementConciliation::getMovementId,
                        FinancialMovementConciliation::getStatus,
                        (a, b) -> a
                ));

        // Deben estar todos los pagos del miembro conciliados (VERIFIED) para habilitar emisiones pendientes.
        for (Long id : ids) {
            if (id == null) continue;
            ConciliationStatus st = byId.get(id);
            if (st != ConciliationStatus.VERIFIED) return false;
        }
        return true;
    }

    /**
     * Devuelve opciones para el combo "Fecha de viaje (en curso)":
     * fechas de ida (aéreos) de emisiones ya emitidas, agrupadas y con cantidad.
     */
    
    /**
     * Devuelve opciones para el combo "Fecha de viaje (en curso)":
     * fechas de ida (aéreos) de emisiones ya emitidas, agrupadas y con cantidad.
     *
     * IMPORTANTE: emitted en MySQL está definido como bit(1). En algunos entornos el mapeo BIT(1) <-> Boolean
     * puede provocar que las queries derivadas (emitted=true) no matcheen. Para evitarlo, usamos SQL nativo
     * comparando explícitamente con b'1'.
     */
    @Transactional
    @GetMapping("/travel-dates")
    public List<EmisionTravelDateOptionDto> listTravelDates(
            @RequestParam("travelMonth") String travelMonth,
            @RequestParam(value = "destination", required = false) String destination
    ) {
        if (travelMonth == null || travelMonth.isBlank()) return List.of();

        String monthLabel = travelMonth.trim();
        String dest = (destination == null || destination.isBlank()) ? null : destination.trim();

        // Permitir que el frontend mande ID (numérico), code o nombre: resolvemos a posibles variantes.
        java.util.List<String> destCandidates = new java.util.ArrayList<>();
        if (dest != null) {
            destCandidates.add(dest);
            try {
                String dnNorm = java.text.Normalizer.normalize(dest, java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
                destCandidates.add(dnNorm);
            } catch (Exception ignored) {
            }

            try {
                if (dest.matches("\\d+")) {
                    Long id = Long.parseLong(dest);
                    TravelDestination d = destinationRepo.findById(id).orElse(null);
                    if (d != null) {
                        if (d.getCode() != null && !d.getCode().isBlank()) destCandidates.add(d.getCode());
                        if (d.getName() != null && !d.getName().isBlank()) destCandidates.add(d.getName());
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Query nativa: join directo member_emision -> travel_request, filtrando emitted=b'1' y devolviendo travel_date_start.
        // Si travel_date_start es NULL, fallback a la menor departure_date de travel_request_air_service, y luego travel_start_date.
        String sql = """
                SELECT
                  COALESCE(
                    tr.travel_date_start,
                    (SELECT MIN(tra.departure_date) FROM travel_request_air_service tra WHERE tra.request_id = tr.id),
                    tr.travel_start_date
                  ) AS travel_date,
                  COUNT(*) AS qty
                FROM member_emision me
                JOIN travel_request tr ON tr.id = me.request_id
                WHERE me.request_id IS NOT NULL
                  AND me.emitted = b'1'
                  AND (
                        TRIM(LOWER(COALESCE(tr.when_label, ''))) = TRIM(LOWER(:month))
                     OR TRIM(LOWER(COALESCE(tr.date_preset_id, ''))) = TRIM(LOWER(:month))
                     OR TRIM(LOWER(COALESCE(me.travel_month, ''))) = TRIM(LOWER(:month))
                  )
                  AND COALESCE(
                    tr.travel_date_start,
                    (SELECT MIN(tra.departure_date) FROM travel_request_air_service tra WHERE tra.request_id = tr.id),
                    tr.travel_start_date
                  ) IS NOT NULL
                """;

        java.util.List<String> destListLower = null;
        if (dest != null) {
            destListLower = destCandidates.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(String::toLowerCase)
                    .distinct()
                    .toList();

            if (!destListLower.isEmpty()) {
                sql += """
                  AND (
                        TRIM(LOWER(COALESCE(tr.destination, ''))) IN (:destList)
                     OR TRIM(LOWER(COALESCE(me.destination, ''))) IN (:destList)
                  )
                """;
            }
        }

        sql += " GROUP BY travel_date ORDER BY travel_date ASC";

        jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
        q.setParameter("month", monthLabel);
        if (destListLower != null && !destListLower.isEmpty()) {
            q.setParameter("destList", destListLower);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        if (rows == null || rows.isEmpty()) return List.of();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        return rows.stream()
                .map(r -> {
                    if (r == null || r.length < 2) return null;

                    LocalDate d = null;
                    Object dateObj = r[0];
                    if (dateObj instanceof java.sql.Date sd) {
                        d = sd.toLocalDate();
                    } else if (dateObj instanceof LocalDate ld) {
                        d = ld;
                    } else if (dateObj != null) {
                        try {
                            d = LocalDate.parse(dateObj.toString());
                        } catch (Exception ignored) {
                            d = null;
                        }
                    }

                    if (d == null) return null;

                    long qty = 0L;
                    Object qtyObj = r[1];
                    if (qtyObj instanceof Number n) {
                        qty = n.longValue();
                    } else if (qtyObj != null) {
                        try {
                            qty = Long.parseLong(qtyObj.toString());
                        } catch (Exception ignored) {
                            qty = 0L;
                        }
                    }

                    return new EmisionTravelDateOptionDto(
                            d.toString(),
                            qty,
                            fmt.format(d) + " (" + qty + " " + (qty == 1 ? "pasajero" : "pasajeros") + ")"
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }


private static boolean matchesDestination(String candidate, String destNorm, String idNorm, String codeNorm, String nameNorm) {
        if (destNorm == null || destNorm.isBlank()) return true;
        String c = normKey(candidate);
        if (c.isBlank()) return false;
        if (destNorm.equals(c)) return true;
        if (idNorm != null && !idNorm.isBlank() && idNorm.equals(c)) return true;
        if (codeNorm != null && !codeNorm.isBlank() && codeNorm.equals(c)) return true;
        if (nameNorm != null && !nameNorm.isBlank() && nameNorm.equals(c)) return true;
        return false;
    }

    private static String normKey(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return "";
        try {
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        } catch (Exception ignored) {
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static int[] parseMonthYearEs(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;

        try {
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        } catch (Exception ignored) {
        }

        java.util.regex.Matcher y = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(s);
        Integer year = null;
        if (y.find()) {
            try {
                year = Integer.parseInt(y.group());
            } catch (Exception ignored) {
            }
        }

        Integer month = null;

        // Mes numérico (ej: 02/2026, 2-2026)
        java.util.regex.Matcher mNum = java.util.regex.Pattern.compile("\\b(0?[1-9]|1[0-2])\\b").matcher(s);
        if (mNum.find()) {
            try {
                month = Integer.parseInt(mNum.group());
            } catch (Exception ignored) {
            }
        }

        if (month == null) {
            java.util.Map<String, Integer> map = java.util.Map.ofEntries(
                    java.util.Map.entry("enero", 1),
                    java.util.Map.entry("ene", 1),
                    java.util.Map.entry("febrero", 2),
                    java.util.Map.entry("feb", 2),
                    java.util.Map.entry("marzo", 3),
                    java.util.Map.entry("mar", 3),
                    java.util.Map.entry("abril", 4),
                    java.util.Map.entry("abr", 4),
                    java.util.Map.entry("mayo", 5),
                    java.util.Map.entry("may", 5),
                    java.util.Map.entry("junio", 6),
                    java.util.Map.entry("jun", 6),
                    java.util.Map.entry("julio", 7),
                    java.util.Map.entry("jul", 7),
                    java.util.Map.entry("agosto", 8),
                    java.util.Map.entry("ago", 8),
                    java.util.Map.entry("septiembre", 9),
                    java.util.Map.entry("setiembre", 9),
                    java.util.Map.entry("sep", 9),
                    java.util.Map.entry("octubre", 10),
                    java.util.Map.entry("oct", 10),
                    java.util.Map.entry("noviembre", 11),
                    java.util.Map.entry("nov", 11),
                    java.util.Map.entry("diciembre", 12),
                    java.util.Map.entry("dic", 12)
            );

            for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
                if (s.contains(e.getKey())) {
                    month = e.getValue();
                    break;
                }
            }
        }

        if (year == null || month == null) return null;
        return new int[]{year, month};
    }

    private Map<LocalDate, Long> listEmittedGroupAirTravelDates(int[] ym, String dest) {
        List<GroupServiceMenuItem> items;
        try {
            items = groupMenuItemRepo.findAllByService_CodeAndOperationStatus(ServiceCode.AEREOS, OperationStatusCode.EMITIDO);
        } catch (Exception ignored) {
            items = null;
        }
        if (items == null || items.isEmpty()) return Map.of();

        Map<LocalDate, Long> counts = new HashMap<>();

        for (GroupServiceMenuItem mi : items) {
            if (mi == null || mi.getId() == null) continue;

            GroupAirService air;
            try {
                air = groupAirServiceRepo.findByMenuItemId(mi.getId()).orElse(null);
            } catch (Exception ignored) {
                air = null;
            }
            if (air == null || air.getDepartureDate() == null) continue;

            LocalDate d = air.getDepartureDate();
            if (ym != null && (d.getYear() != ym[0] || d.getMonthValue() != ym[1])) continue;

            String airDest = air.getDestination();
            if (airDest == null || airDest.isBlank()) {
                try {
                    airDest = mi.getGroup() == null ? null : mi.getGroup().getDestination();
                } catch (Exception ignored) {
                    airDest = null;
                }
            }

            if (dest != null && !dest.isBlank()) {
                if (airDest == null || !airDest.trim().equalsIgnoreCase(dest.trim())) continue;
            }

            Long groupId = null;
            try {
                groupId = mi.getGroup() == null ? null : mi.getGroup().getId();
            } catch (Exception ignored) {
            }

            long pax = 1L;
            if (groupId != null) {
                try {
                    pax = travelRequestRepo.countByGroupId(groupId);
                } catch (Exception ignored) {
                    pax = 1L;
                }
                if (pax <= 0) pax = 1L;
            }

            counts.merge(d, pax, Long::sum);
        }

        return counts;
    }


    /**
     * Marca como emitido (para ocultar en la lista de "Emisiones pendientes").
     */
    @PostMapping("/{id}/emit")
    @Transactional
    public ResponseEntity<?> markEmitted(@PathVariable("id") Long emisionId) {
        MemberEmision emision = memberEmisionRepo.findById(emisionId)
                .orElseThrow(() -> new NotFoundException("No existe member_emision id=" + emisionId));

        if (emision.getEmitted() == null || !emision.getEmitted()) {
            emision.setEmitted(true);
            emision.setEmittedAt(Instant.now());
            emision.setStatus("EMITIDO");
            memberEmisionRepo.save(emision);
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Actualiza datos del pasajero dentro de member_emision (ej. nombre/apellido y edad).
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updatePassenger(@PathVariable("id") Long emisionId, @RequestBody MemberEmisionUpdateRequest body) {
        MemberEmision emision = memberEmisionRepo.findById(emisionId)
                .orElseThrow(() -> new NotFoundException("No existe member_emision id=" + emisionId));

        if (body != null) {
            if (body.fullName() != null) {
                emision.setFullName(body.fullName().trim());
            }
            if (body.age() != null) {
                emision.setAge(body.age());
            }
            memberEmisionRepo.save(emision);
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    private MemberEmisionPendingDto toDto(MemberEmision e) {
        return MemberEmisionPendingDto.builder()
                .emisionId(e.getId())
                .requestId(e.getRequestId())
                .destination(e.getDestination())
                .travelMonth(e.getTravelMonth())
                .fullName(e.getFullName())
                .age(e.getAge())
                .companionType(e.getCompanionType())
                .gender(e.getGender())
                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt())
                .createdAt(e.getCreatedAt())
                .status(e.getStatus())
                .emitted(e.getEmitted())
                .emittedAt(e.getEmittedAt())
                .build();
    }
}
