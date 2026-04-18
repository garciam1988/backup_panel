package app.coincidir.api.service;

import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.MemberOptionalServiceCode;
import app.coincidir.api.domain.MemberOptionalExcursionService;
import app.coincidir.api.domain.MemberOptionalServiceMenuItem;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.expense.ExpenseStatus;
import app.coincidir.api.domain.expense.ExpenseType;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.domain.payment.ServicePaymentForm;
import app.coincidir.api.domain.payment.PaymentOneTimeMethod;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import app.coincidir.api.domain.payment.InstallmentStatus;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.domain.payment.ServicePaymentPlan;
import app.coincidir.api.domain.payment.ServicePaymentRecord;
import app.coincidir.api.domain.payment.OptionalServicePaymentPlan;
import app.coincidir.api.domain.payment.OptionalServicePaymentRecord;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.repository.GroupAccommodationServiceRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.repository.MemberOptionalExcursionServiceRepository;
import app.coincidir.api.repository.MemberOptionalServiceMenuItemRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.OptionalServicePaymentPlanRepository;
import app.coincidir.api.repository.OptionalServicePaymentRecordRepository;
import app.coincidir.api.repository.ServicePaymentPlanRepository;
import app.coincidir.api.repository.ServicePaymentRecordRepository;
import app.coincidir.api.web.dto.*;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupFerryService;
import app.coincidir.api.domain.GroupTransferService;
import app.coincidir.api.domain.GroupDestinationTransferService;
import app.coincidir.api.domain.MemberEmision;
import app.coincidir.api.domain.MemberAirService;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.repository.GroupFerryServiceRepository;
import app.coincidir.api.repository.GroupTransferServiceRepository;
import app.coincidir.api.repository.GroupDestinationTransferServiceRepository;
import app.coincidir.api.repository.MemberEmisionRepository;
import app.coincidir.api.repository.MemberAirServiceRepository;
import app.coincidir.api.repository.TravelGroupRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor
public class OperationsPaymentsService {

    private final GroupServiceMenuItemRepository menuItemRepo;
    private final MemberOptionalServiceMenuItemRepository memberOptionalMenuItemRepo;
    private final MemberOptionalExcursionServiceRepository memberOptionalExcursionRepo;
    private final GroupAccommodationServiceRepository accommodationRepo;
    private final ServicePaymentPlanRepository planRepo;
    private final ServicePaymentRecordRepository recordRepo;
    private final OptionalServicePaymentPlanRepository optionalPlanRepo;
    private final OptionalServicePaymentRecordRepository optionalRecordRepo;
    private final MemberPaymentPlanRepository memberPaymentPlanRepo;
    private final MemberPaymentRecordRepository memberPaymentRecordRepo;
    private final ExpenseRepository expenseRepo;
    private final TravelGroupRepository travelGroupRepo;
    private final GroupAirServiceRepository groupAirRepo;
    private final GroupFerryServiceRepository groupFerryRepo;
    private final GroupTransferServiceRepository groupTransferRepo;
    private final GroupDestinationTransferServiceRepository groupDestinationTransferRepo;
    private final MemberAirServiceRepository memberAirRepo;
    private final MemberEmisionRepository memberEmisionRepo;
    private final OperationsRoleGuardService operationsRoleGuardService;
    private final AuthorizationCodeService authCodeService;

    public record ReceiptDownload(byte[] bytes, String contentType, String fileName) {}

    
    @Transactional(readOnly = true)
    public ServicePaymentPlanDto getPayments(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
        if (menuItem == null) {
            return getOptionalExcursionPayments(groupId, menuItemId);
        }

        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId).orElse(null);

        List<ServicePaymentRecordDto> records = (plan == null)
                ? List.of()
                : recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId())
                .stream().map(ServicePaymentRecordDto::fromEntity).toList();

        // AÉREOS: cache de códigos de reserva por pasajero según pagos (evita pisar códigos entre registros)
        Map<Long, String> reservationFromPaymentByMember = new HashMap<>();
        try {
            for (ServicePaymentRecordDto rr : records) {
                if (rr == null) continue;
                Long mid = rr.getMemberId();
                String rc = rr.getReservationCode();
                if (mid != null && rc != null && !rc.isBlank() && !reservationFromPaymentByMember.containsKey(mid)) {
                    reservationFromPaymentByMember.put(mid, rc.trim());
                }
            }
        } catch (Exception ignored) {
        }

        // También devolver "pagos" que existen como Expenses (incluye pagos tempranos desde Admin Panel).
        List<Expense> expenses = new ArrayList<>();
        try {
            expenses.addAll(expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId));
        } catch (Exception ignored) {
        }

        ServiceCode sc = (menuItem.getService() != null) ? menuItem.getService().getCode() : null;

        // Para Ferry el flujo actual usa exclusivamente ServicePaymentRecord. Los Expenses pueden quedar como legacy
        // y no deben mostrarse en este endpoint para evitar inconsistencias.
        if (sc == ServiceCode.FERRY) {
            expenses.clear();
        }


        if (sc == ServiceCode.AEREOS) {
            // Legacy / pagos aéreos tempranos (admin) que pudieron quedar sin menuItemId, se encuentran por notes
            try {
                expenses.addAll(expenseRepo.findAllByGroupIdAndNotesContaining(groupId, "Pago aéreos temporal"));
            } catch (Exception ignored) {
            }
        }

        if (sc == ServiceCode.AEREOS) {
            // Emisiones / pagos legacy por pasajero (por ejemplo desde "Emisiones pendientes" / alta manual)
            // que pudieron quedar sin groupId/menuItemId. Se encuentran por tokens en notes (requestId/memberId)
            // y se filtran por pasajeros del grupo.
            try {
                TravelGroup gForLegacy = travelGroupRepo.fetchDetail(groupId).orElse(null);
                List<TravelRequest> membersForLegacy = (gForLegacy != null && gForLegacy.getMembers() != null) ? gForLegacy.getMembers() : List.of();
                for (TravelRequest tr : membersForLegacy) {
                    if (tr == null || tr.getId() == null) continue;
                    Long mid = tr.getId();

                    // Buscar por requestId= y memberId= (ambos formatos se usaron históricamente en notes)
                    List<Expense> byReq = expenseRepo.findAllByNotesContaining("requestId=" + mid);
                    List<Expense> byMem = expenseRepo.findAllByNotesContaining("memberId=" + mid);

                    for (Expense e : byReq) {
                        if (e == null || e.getId() == null) continue;
                        // Filtrar a aéreos/emisión para no arrastrar gastos ajenos
                        String c = e.getConcept();
                        if (c != null) {
                            String lc = c.toLowerCase();
                            if (!lc.contains("aereo") && !lc.contains("aére") && !lc.contains("emision")) continue;
                        }
                        // Si ya viene linkeado a otro grupo, no mezclar
                        if (e.getGroupId() != null && !Objects.equals(e.getGroupId(), groupId)) continue;
                        if (e.getMenuItemId() != null && !Objects.equals(e.getMenuItemId(), menuItemId)) continue;
                        expenses.add(e);
                    }
                    for (Expense e : byMem) {
                        if (e == null || e.getId() == null) continue;
                        String c = e.getConcept();
                        if (c != null) {
                            String lc = c.toLowerCase();
                            if (!lc.contains("aereo") && !lc.contains("aére") && !lc.contains("emision")) continue;
                        }
                        if (e.getGroupId() != null && !Objects.equals(e.getGroupId(), groupId)) continue;
                        if (e.getMenuItemId() != null && !Objects.equals(e.getMenuItemId(), menuItemId)) continue;
                        expenses.add(e);
                    }
                }
            } catch (Exception ignored) {
            }
        }


        // Deduplicar por id y mapear a DTO
        LinkedHashMap<Long, Expense> uniq = new LinkedHashMap<>();
        for (Expense e : expenses) {
            if (e == null || e.getId() == null) continue;
            uniq.putIfAbsent(e.getId(), e);
        }

        List<ExpensePaymentRecordDto> expenseRecords;
        if (sc == ServiceCode.AEREOS) {
            // Enriquecer con info del pasajero + código de reserva + estado de emisión
            TravelGroup g = null;
            try {
                g = travelGroupRepo.fetchDetail(groupId).orElse(null);
            } catch (Exception ignored) {
            }
            List<TravelRequest> members = (g != null && g.getMembers() != null) ? g.getMembers() : List.of();

            Map<Long, TravelRequest> membersById = new HashMap<>();
            Map<String, Long> membersByNormName = new HashMap<>();
            Set<Long> memberIds = new HashSet<>();
            for (TravelRequest tr : members) {
                if (tr == null || tr.getId() == null) continue;
                membersById.put(tr.getId(), tr);
                memberIds.add(tr.getId());
                String nn = normalizeName(tr.getName());
                if (nn != null && !nn.isBlank() && !membersByNormName.containsKey(nn)) {
                    membersByNormName.put(nn, tr.getId());
                }
            }

            Map<Long, String> reservationByMember = new HashMap<>();
            try {
                for (MemberAirService mas : memberAirRepo.findByMenuItemId(menuItemId)) {
                    if (mas == null || mas.getMember() == null || mas.getMember().getId() == null) continue;
                    String rc = mas.getReservationCode();
                    if (rc != null && !rc.isBlank()) {
                        reservationByMember.put(mas.getMember().getId(), rc.trim());
                    }
                }
            } catch (Exception ignored) {
            }

            // Si la emisión ya fue realizada (member_emision.emitted = 1), no debe figurar como pendiente.
            Set<Long> emittedMembers = new HashSet<>();
            try {
                if (!memberIds.isEmpty()) {
                    List<MemberEmision> ems = memberEmisionRepo.findByRequestIdInAndEmittedIsTrue(new ArrayList<>(memberIds));
                    for (MemberEmision me : ems) {
                        if (me == null || me.getRequestId() == null) continue;
                        emittedMembers.add(me.getRequestId());
                    }
                }
            } catch (Exception ignored) {
            }

            // IMPORTANTE: variables capturadas por lambdas deben ser final o efectivamente final.
            // Por eso calculamos en un temporal y luego asignamos una única vez al valor final.
            String groupReservationTmp = null;
            try {
                groupReservationTmp = groupAirRepo.findByMenuItemId(menuItemId)
                        .map(GroupAirService::getReservationCode)
                        .orElse(null);
            } catch (Exception ignored) {
            }
            final String groupReservation = (groupReservationTmp != null && !groupReservationTmp.isBlank())
                    ? groupReservationTmp.trim()
                    : null;

            expenseRecords = uniq.values().stream()
                    .sorted((a, b) -> {
                        LocalDate da = a.getDate();
                        LocalDate db = b.getDate();
                        int cmp = 0;
                        if (da != null && db != null) cmp = da.compareTo(db);
                        else if (da == null && db != null) cmp = -1;
                        else if (da != null) cmp = 1;
                        if (cmp != 0) return cmp;
                        return Long.compare(a.getId(), b.getId());
                    })
                    .map(e -> {
                        Long memberId = inferMemberIdFromExpense(e, memberIds, membersByNormName);
                        TravelRequest tr = (memberId != null) ? membersById.get(memberId) : null;

                        String paxName = null;
                        if (tr != null && tr.getName() != null && !tr.getName().isBlank()) {
                            paxName = tr.getName().trim();
                        } else {
                            paxName = extractPassengerNameFromConcept(e.getConcept());
                        }

                        String rc = extractReservationCodeFromNotes(e.getNotes());
                        if (rc == null || rc.isBlank()) {
                            rc = (memberId != null) ? reservationFromPaymentByMember.get(memberId) : null;
                        }
                        if (rc == null || rc.isBlank()) {
                            rc = (memberId != null) ? reservationByMember.get(memberId) : null;
                        }
                        // Solo usar el código de reserva del grupo si no se pudo inferir un pasajero.
                        if ((rc == null || rc.isBlank()) && memberId == null && groupReservation != null) rc = groupReservation;

                        boolean emittedByFlag = (memberId != null) && emittedMembers.contains(memberId);
                        String emissionStatus = (emittedByFlag || (rc != null && !rc.isBlank())) ? "EMITIDO" : "PENDIENTE";

                        // Para que el frontend pueda abrir comprobante incluso si no hay ServicePaymentRecord:
                        Long effectiveSprId = (e.getServicePaymentRecordId() != null) ? e.getServicePaymentRecordId() : e.getId();

                        return ExpensePaymentRecordDto.enriched(
                                e,
                                effectiveSprId,
                                memberId,
                                paxName,
                                rc,
                                emissionStatus
                        );
                    })
                    .toList();
        } else {
            expenseRecords = uniq.values().stream()
                    .sorted((a, b) -> {
                        LocalDate da = a.getDate();
                        LocalDate db = b.getDate();
                        int cmp = 0;
                        if (da != null && db != null) cmp = da.compareTo(db);
                        else if (da == null && db != null) cmp = -1;
                        else if (da != null) cmp = 1;
                        if (cmp != 0) return cmp;
                        return Long.compare(a.getId(), b.getId());
                    })
                    .map(ExpensePaymentRecordDto::fromEntity)
                    .toList();
        }

        if (plan == null) {
            return ServicePaymentPlanDto.builder()
                    .id(null)
                    .groupId(groupId)
                    .menuItemId(menuItemId)
                    .paymentForm(null)
                    .totalAmount(null)
                    .currency(null)
                    .records(records)
                    .expenseRecords(expenseRecords)
                    .build();
        }

        return ServicePaymentPlanDto.fromEntity(plan, records, expenseRecords);
    }

    @Transactional(readOnly = true)
    ServicePaymentPlanDto getOptionalExcursionPayments(Long groupId, Long menuItemId) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new NotFoundException("Menu item no encontrado");
        }

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(menuItemId).orElse(null);
        List<ServicePaymentRecordDto> records = (plan == null)
                ? List.of()
                : optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId())
                .stream().map(ServicePaymentRecordDto::fromOptionalEntity).toList();

        if (plan == null) {
            return ServicePaymentPlanDto.builder()
                    .id(null)
                    .groupId(groupId)
                    .menuItemId(menuItemId)
                    .paymentForm(null)
                    .totalAmount(null)
                    .currency(null)
                    .records(records)
                    .expenseRecords(List.of())
                    .build();
        }

        return ServicePaymentPlanDto.fromOptionalEntity(plan, records);
    }

@Transactional
    public ServicePaymentPlanDto upsertPlan(Long groupId, Long menuItemId, UpsertServicePaymentPlanRequest req) {
        GroupServiceMenuItem menuItem = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
        if (menuItem == null) {
            return upsertOptionalExcursionPlan(groupId, menuItemId, req);
        }

        ServicePaymentForm form;
        try {
            form = ServicePaymentForm.fromString(req.paymentForm());
        } catch (Exception ex) {
            throw new BadRequestException("Forma de pago inválida");
        }

        ServiceCode sc = menuItem.getService() != null ? menuItem.getService().getCode() : null;


        if (sc != ServiceCode.ALOJAMIENTOS && form != ServicePaymentForm.TOTAL) {
            throw new BadRequestException("Para este servicio la forma de pago debe ser TOTAL");
        }

        BigDecimal total = req.totalAmount();

        String currency = (req.currency() == null || req.currency().isBlank()) ? "ARS" : req.currency().trim();

        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    ServicePaymentPlan p = new ServicePaymentPlan();
                    p.setMenuItem(menuItem);
                    p.setCreatedAt(Instant.now());
                    return p;
                });

        plan.setPaymentForm(form);
        plan.setTotalAmount(total);
        plan.setCurrency(currency);
        plan.setUpdatedAt(Instant.now());
        ServicePaymentPlan saved = planRepo.save(plan);

        List<ServicePaymentRecordDto> records = (saved.getId() == null)
                ? List.of()
                : recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(saved.getId()).stream()
                .map(ServicePaymentRecordDto::fromEntity)
                .toList();
        return ServicePaymentPlanDto.fromEntity(saved, records);
    }

    @Transactional
    ServicePaymentPlanDto upsertOptionalExcursionPlan(Long groupId, Long menuItemId, UpsertServicePaymentPlanRequest req) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new NotFoundException("Menu item no encontrado");
        }

        ServicePaymentForm form;
        try {
            form = ServicePaymentForm.fromString(req.paymentForm());
        } catch (Exception ex) {
            throw new BadRequestException("Forma de pago inválida");
        }

        if (form != ServicePaymentForm.TOTAL) {
            throw new BadRequestException("Para este servicio la forma de pago debe ser TOTAL");
        }

        BigDecimal total = req.totalAmount();

        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency().trim();

        // Para Excursiones (opcionales), el Total a cobrar se toma de Venta (USD).
        // Si Venta (USD) no está cargada (> 0), no se permite configurar el plan.
        BigDecimal saleFromService = null;
        try {
            Long memberId = item.getMember() != null ? item.getMember().getId() : null;
            if (memberId != null) {
                saleFromService = memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                        .map(MemberOptionalExcursionService::getSale)
                        .orElse(null);
            }
        } catch (Exception ignored) {
        }

        if (saleFromService == null || saleFromService.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Primero debe cargar la Venta (USD) del servicio");
        }

        // Normalizar a 2 decimales para evitar diferencias de precisión (JS float -> JSON)
        try {
            total = saleFromService.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            total = saleFromService;
        }
        currency = "USD";

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    OptionalServicePaymentPlan p = new OptionalServicePaymentPlan();
                    p.setMenuItem(item);
                    return p;
                });

        // No permitir que el total sea menor a lo ya pagado
        if (plan.getId() != null) {
            BigDecimal paid = optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId())
                    .stream()
                    .map(OptionalServicePaymentRecord::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (paid.compareTo(total) > 0) {
                throw new BadRequestException("El total a cobrar no puede ser menor al total ya pagado");
            }
        }

        plan.setPaymentForm(form);
        plan.setTotalAmount(total);
        plan.setCurrency(currency);
        // Asegurar timestamps para evitar errores de "not-null property" si el callback @PrePersist no corre.
        Instant now = Instant.now();
        if (plan.getCreatedAt() == null) {
            plan.setCreatedAt(now);
        }
        plan.setUpdatedAt(now);
        OptionalServicePaymentPlan saved = optionalPlanRepo.save(plan);

        List<ServicePaymentRecordDto> records = (saved.getId() == null)
                ? List.of()
                : optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(saved.getId())
                .stream().map(ServicePaymentRecordDto::fromOptionalEntity)
                .toList();

        return ServicePaymentPlanDto.fromOptionalEntity(saved, records);
    }

    @Transactional
    public ServicePaymentPlanDto createRecord(Long groupId, Long menuItemId, CreateServicePaymentRecordRequest req) {
        GroupServiceMenuItem menuItem = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
        if (menuItem == null) {
            return createOptionalExcursionPaymentRecord(groupId, menuItemId, req);
        }
        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId)
                .orElseThrow(() -> new BadRequestException("Primero debe configurar la forma de pago"));

        BigDecimal amount = req.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("El importe debe ser mayor a 0");
        }

        LocalDate paymentDate;
        try {
            paymentDate = LocalDate.parse(req.paymentDate().trim());
        } catch (Exception ex) {
            throw new BadRequestException("Fecha de pago inválida");
        }

        String currency = (req.currency() == null || req.currency().isBlank())
                ? (plan.getCurrency() != null ? plan.getCurrency() : "ARS")
                : req.currency().trim();
        ServiceCode sc = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        // Para AÉREOS el código de reserva se valida en la acción de "Pagar/Emitir".
        // Registrar un pago NO debe bloquearse por no tener aún el código de reserva.
        String reservationCode = normalizeReservationCode(req.reservationCode());
        String flightNumber = normalizeFlightNumber(req.flightNumber());


        String oneTimeMethod = (req.oneTimeMethod() != null && !req.oneTimeMethod().isBlank()) ? req.oneTimeMethod().trim() : null;
        if (oneTimeMethod == null) {
            throw new BadRequestException("Método de pago requerido");
        }
        PaymentOneTimeMethod methodEnum;
        try {
            methodEnum = parseOneTimeMethod(oneTimeMethod);
        } catch (Exception ex) {
            throw new BadRequestException("Método de pago inválido");
        }

        Long bankId = req.bankId();
        Long cardId = req.cardId();
        String cardNumber = normalizeCardNumber(req.cardNumber());

        if (methodEnum == PaymentOneTimeMethod.TARJETA_DEBITO || methodEnum == PaymentOneTimeMethod.TARJETA_CREDITO) {
            if (bankId == null) throw new BadRequestException("Debe seleccionar el banco");
            // cardId es opcional: si no viene, se usa cardNumber (últimos 4 dígitos).
            if (cardId == null && (cardNumber == null || cardNumber.isBlank())) {
                throw new BadRequestException("Debe completar la tarjeta");
            }
        } else {
            bankId = null;
            cardId = null;
            cardNumber = null;
        }

        if (sc == ServiceCode.AEREOS) {
            Long memberId = req.memberId();
            if (memberId == null) {
                throw new BadRequestException("memberId requerido para pagos de aéreos");
            }

            // Un pago por pasajero (flujo de emisión): se valida por ServicePaymentRecord.
            try {
                if (plan.getId() != null) {
                    boolean alreadyHas = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                            .anyMatch(x -> x != null && x.getMemberId() != null && Objects.equals(x.getMemberId(), memberId));
                    if (alreadyHas) {
                        throw new BadRequestException("El pasajero ya tiene un pago registrado");
                    }
                }
            } catch (BadRequestException ex) {
                throw ex;
            } catch (Exception ignored) {
            }

            // Validación: si la suma de pagos supera el valor cotizado por más de 2%, requiere autorización ADMIN
            try {
                BigDecimal quoted = null;
                try {
                    quoted = groupAirRepo.findByMenuItemId(menuItemId)
                            .map(GroupAirService::getQuotedValue)
                            .orElse(null);
                } catch (Exception ignored2) {
                }

                if (quoted == null || quoted.compareTo(BigDecimal.ZERO) <= 0) {
                    try {
                        quoted = memberAirRepo.sumQuotedValueByGroupId(groupId);
                    } catch (Exception ignored2) {
                    }
                }

                if (quoted != null && quoted.compareTo(BigDecimal.ZERO) > 0 && plan.getId() != null) {
                    BigDecimal paidSoFar = sumPaid(plan.getId());
                    if (paidSoFar == null) paidSoFar = BigDecimal.ZERO;
                    BigDecimal totalAfter = paidSoFar.add(amount != null ? amount : BigDecimal.ZERO);
                    BigDecimal threshold = quoted.multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP);
                    if (totalAfter.compareTo(threshold) > 0) {
                        authCodeService.requireAuthorizationIfNotAdmin("ADMIN");
                    }
                }
            } catch (Exception ignored2) {
                // No bloquear por errores de cálculo
            }
        } else if (sc == ServiceCode.FERRY) {
            // Ferry: solo 1 pago y el importe no puede superar el Total costo del servicio.
            BigDecimal totalCost = null;
            try {
                totalCost = groupFerryRepo.findByMenuItemId(menuItemId)
                        .map(GroupFerryService::getTotalCost)
                        .orElse(null);
            } catch (Exception ignored) {
            }

            if (totalCost == null || totalCost.compareTo(BigDecimal.ZERO) <= 0) {
                // Si no hay Total costo definido, se lo asignamos automáticamente con el importe del pago
                totalCost = amount;
                try {
                    GroupFerryService ferryService = groupFerryRepo.findByMenuItemId(menuItemId).orElse(null);
                    if (ferryService != null) {
                        ferryService.setTotalCost(amount);
                        ferryService.setTotalCostUpdatedAt(java.time.Instant.now());
                        groupFerryRepo.save(ferryService);
                    }
                } catch (Exception ignored) {
                }
            }

            boolean hasExisting = false;
            try {
                hasExisting = plan.getId() != null && !recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).isEmpty();
            } catch (Exception ignored) {
            }

            // Ferry: validar existencia SOLO por ServicePaymentRecord (los Expenses pueden quedar como legacy/orfandad)

            if (hasExisting) {
                throw new BadRequestException("Para Ferry solo se permite registrar 1 pago. Eliminá el existente para cargar uno nuevo");
            }

            if (amount.compareTo(totalCost) > 0) {
                throw new BadRequestException("No se puede cargar un pago mayor al total a cobrar");
            }
        } else if (sc == ServiceCode.ALOJAMIENTOS) {
            // ALOJAMIENTOS (Forma de pago: Seña): solo se permite registrar 1 pago.
            // El FE oculta el form cuando ya existe una seña; reforzamos con validación server-side.
            if (plan.getPaymentForm() == ServicePaymentForm.SENA) {
                boolean hasExisting = false;
                try {
                    hasExisting = plan.getId() != null && !recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).isEmpty();
                } catch (Exception ignored) {
                }
                if (hasExisting) {
                    throw new BadRequestException("Para Alojamiento con forma de pago Seña solo se permite registrar 1 pago. Eliminá el existente para cargar uno nuevo");
                }
            }

            // Validación: si la suma de pagos supera el valor cotizado por más de 2%, requiere autorización ADMIN
            try {
                BigDecimal quoted = null;
                try {
                    quoted = accommodationRepo.findByMenuItemId(menuItemId)
                            .map(GroupAccommodationService::getQuotedValue)
                            .orElse(null);
                } catch (Exception ignored) {
                }

                if (quoted != null && quoted.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal paidSoFar = BigDecimal.ZERO;
                    try {
                        BigDecimal x = sumAccommodationPaid(groupId, menuItemId);
                        if (x != null) paidSoFar = x;
                    } catch (Exception ignored2) {
                    }
                    BigDecimal totalAfter = paidSoFar.add(amount != null ? amount : BigDecimal.ZERO);
                    BigDecimal threshold = quoted.multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP);
                    if (totalAfter.compareTo(threshold) > 0) {
                        authCodeService.requireAuthorizationIfNotAdmin("ADMIN");
                    }
                }
            } catch (Exception ignored) {
                // No bloquear por errores de cálculo
            }

        }




        // Persistir (NO se valida contra total a cobrar: el importe puede diferir y se admiten múltiples pagos)
        ServicePaymentRecord r = new ServicePaymentRecord();
        r.setPlan(plan);
        r.setAmount(amount);
        r.setCurrency(currency);
        r.setPaymentDate(paymentDate);
        // ALOJAMIENTOS (Seña): fecha prevista de cancelación del pago total
        LocalDate totalPaymentCancellationDate = null;
        try {
            String rawCancel = req.totalPaymentCancellationDate();
            if (rawCancel != null && !rawCancel.isBlank()) {
                totalPaymentCancellationDate = LocalDate.parse(rawCancel.trim());
            }
        } catch (Exception ex) {
            throw new BadRequestException("Fecha cancelacion pago total inválida");
        }
        if (sc == ServiceCode.ALOJAMIENTOS && plan.getPaymentForm() == ServicePaymentForm.SENA) {
            r.setTotalPaymentCancellationDate(totalPaymentCancellationDate);
        } else {
            r.setTotalPaymentCancellationDate(null);
        }
        r.setOneTimeMethod(oneTimeMethod);
        r.setReceiptNumber(req.receiptNumber() != null && !req.receiptNumber().isBlank() ? req.receiptNumber().trim() : null);
        r.setReceiptLast4(req.receiptLast4() != null && !req.receiptLast4().isBlank() ? req.receiptLast4().trim() : null);
        r.setReservationCode(reservationCode);
        r.setFlightNumber(flightNumber);
        r.setMemberId(req.memberId());
        r.setPassengerFullName(req.passengerFullName() != null && !req.passengerFullName().isBlank() ? req.passengerFullName().trim() : null);
        r.setBankId(bankId);
        r.setCardId(cardId);
        r.setCardNumber(cardNumber);
        r.setCreatedAt(Instant.now());
        recordRepo.save(r);

        if (sc == ServiceCode.AEREOS) {
            // AÉREOS: 1 solo registro en expenses por operación/batch. El ID de operación debe verse en cada pago.
            Long expenseId = createOrUpdateAirEmissionExpenseAndLink(groupId, menuItem, plan, r, req);
            if (expenseId != null) {
                r.setExpenseId(expenseId);
                recordRepo.save(r);
            }
            // Total costo = suma de pagos registrados
            try {
                syncAirTotalCostFromPayments(menuItemId, plan);
            } catch (Exception ignored) {
            }
        } else {
            createExpenseForPayment(menuItem, plan, r, req.memberId(), req.passengerFullName(), r.getReservationCode());
            if (sc == ServiceCode.ALOJAMIENTOS) {
                try {
                    syncAccommodationTotalCostFromPayments(groupId, menuItemId);
                } catch (Exception ignored) {
                }
            }
        }

        // Para AÉREOS: si el código de reserva se cargó dentro del pago, sincronizarlo para TODOS los miembros del pago.
        try {
            ServiceCode sc2 = menuItem.getService() != null ? menuItem.getService().getCode() : null;
            if (sc2 == ServiceCode.AEREOS && r.getReservationCode() != null && !r.getReservationCode().isBlank()) {
                List<Long> allMemberIds = new java.util.ArrayList<>();
                if (req.memberIds() != null) {
                    for (Long mid : req.memberIds()) {
                        if (mid != null) allMemberIds.add(mid);
                    }
                }
                if (req.memberId() != null && !allMemberIds.contains(req.memberId())) {
                    allMemberIds.add(req.memberId());
                }
                for (Long mid : allMemberIds) {
                    syncAirReservationCode(groupId, menuItem, mid, r.getReservationCode());
                }
            }
        } catch (Exception ignored) {
            // No debe bloquear el registro del pago
        }

        // AÉREOS: al registrar el pago desde el servicio, considerarlo emitido y ocultarlo de "Emisiones pendientes".
        if (sc == ServiceCode.AEREOS && req.memberId() != null) {
            try {
                memberEmisionRepo.findTopByRequestIdOrderByCreatedAtDesc(req.memberId()).ifPresent(em -> {
                    try {
                        Boolean already = em.getEmitted();
                        if (already == null || !already) {
                            em.setEmitted(true);
                            em.setEmittedAt(Instant.now());
                            em.setStatus("EMITIDO");
                            memberEmisionRepo.save(em);
                        }
                    } catch (Exception ignored2) {
                    }
                });
            } catch (Exception ignored) {
            }
        }

        plan.setUpdatedAt(Instant.now());
        planRepo.save(plan);

        // Auto-actualizar estado (PENDIENTE/EMITIDO) según totalCost vs pagos
        refreshAutoOperationStatusIfApplicable(groupId, menuItem);

        // Devolver el mismo payload que GET /payments (incluye expenseRecords enriquecidos)
        return getPayments(groupId, menuItemId);
    }

    @Transactional
    ServicePaymentPlanDto createOptionalExcursionPaymentRecord(Long groupId, Long menuItemId, CreateServicePaymentRecordRequest req) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new NotFoundException("Menu item no encontrado");
        }

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(menuItemId)
                .orElseThrow(() -> new BadRequestException("Primero debe configurar la forma de pago"));

        // Validación de completitud (el FE muestra modal con faltantes)
        List<String> missing = new ArrayList<>();
        BigDecimal amount = req.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) missing.add("Importe");
        String paymentDateRaw = (req.paymentDate() != null) ? req.paymentDate().trim() : null;
        if (paymentDateRaw == null || paymentDateRaw.isBlank()) missing.add("Fecha de pago");
        String oneTimeMethod = (req.oneTimeMethod() != null && !req.oneTimeMethod().isBlank()) ? req.oneTimeMethod().trim() : null;
        if (oneTimeMethod == null) missing.add("Método de pago");

        PaymentOneTimeMethod methodEnumForMissing = null;
        if (oneTimeMethod != null) {
            try {
                methodEnumForMissing = parseOneTimeMethod(oneTimeMethod);
            } catch (Exception ignored) {
                // se valida más abajo con mensaje específico
            }
        }

        Long bankId = req.bankId();
        Long cardId = req.cardId();
        String cardNumber = normalizeCardNumber(req.cardNumber());
        if (methodEnumForMissing == PaymentOneTimeMethod.TARJETA_DEBITO || methodEnumForMissing == PaymentOneTimeMethod.TARJETA_CREDITO) {
            if (bankId == null) missing.add("Banco");
            // Se acepta Tarjeta por catálogo (cardId) o últimos 4 dígitos (cardNumber).
            if (cardId == null && (cardNumber == null || cardNumber.isBlank())) missing.add("Tarjeta");
        }

        if (!missing.isEmpty()) {
            throw new BadRequestException("Faltan completar datos: " + String.join(", ", missing));
        }

        LocalDate paymentDate;
        try {
            paymentDate = LocalDate.parse(paymentDateRaw);
        } catch (Exception ex) {
            throw new BadRequestException("Fecha de pago inválida");
        }

        BigDecimal total = null;

        // Para Excursiones (opcionales), el tope de pago se valida contra Venta (USD).
        BigDecimal saleFromService = null;
        try {
            Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
            if (memberId != null) {
                saleFromService = memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                        .map(MemberOptionalExcursionService::getSale)
                        .orElse(null);
            }
        } catch (Exception ignored) {
        }

        if (saleFromService == null || saleFromService.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Primero debe cargar la Venta (USD) del servicio");
        }

        total = saleFromService;

        // Normalizar a 2 decimales para evitar falsos positivos por diferencias de precisión (JS float -> JSON)
        try {
            amount = amount.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
        }
        try {
            total = total.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
        }

        // Si el usuario modificó "Venta (USD)" en el front y registró el pago sin guardar el servicio,
        // el backend puede tener un valor desactualizado. Como "Total a cobrar" deriva de Venta (USD)
        // y está deshabilitado, tomamos el importe del pago como fuente de verdad y sincronizamos la venta.
        try {
            if (amount != null && total != null && amount.compareTo(total) != 0) {
                Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
                if (memberId != null) {
                    BigDecimal finalAmount = amount;
                    memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(menuItemId, memberId).ifPresent(svc -> {
                        try {
                            svc.setSale(finalAmount);
                            memberOptionalExcursionRepo.save(svc);
                        } catch (Exception ignored2) {
                        }
                    });
                }
                total = amount;
            }
        } catch (Exception ignored) {
        }

        // Un solo pago para opcionales (forma TOTAL)
        if (plan.getId() != null) {
            List<OptionalServicePaymentRecord> existing = optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId());
            if (existing != null && !existing.isEmpty()) {
                throw new BadRequestException("Ya existe un pago registrado");
            }
        }

        if (amount.compareTo(total) > 0) {
            throw new BadRequestException("No se puede cargar un pago mayor al total a cobrar");
        }

        String currency = (req.currency() == null || req.currency().isBlank())
                ? (plan.getCurrency() != null ? plan.getCurrency() : "USD")
                : req.currency().trim();

        PaymentOneTimeMethod methodEnum;
        try {
            methodEnum = parseOneTimeMethod(oneTimeMethod);
        } catch (Exception ex) {
            throw new BadRequestException("Método de pago inválido");
        }

        if (methodEnum == PaymentOneTimeMethod.TARJETA_DEBITO || methodEnum == PaymentOneTimeMethod.TARJETA_CREDITO) {
            if (bankId == null) throw new BadRequestException("Debe seleccionar el banco");
            // cardId es opcional: si no viene, se usa cardNumber (últimos 4 dígitos).
            if (cardId == null && (cardNumber == null || cardNumber.isBlank())) {
                throw new BadRequestException("Debe completar la tarjeta");
            }
        } else {
            bankId = null;
            cardId = null;
            cardNumber = null;
        }

        OptionalServicePaymentRecord r = new OptionalServicePaymentRecord();
        r.setPlan(plan);
        r.setAmount(amount);
        r.setCurrency(currency);
        r.setPaymentDate(paymentDate);
        r.setOneTimeMethod(oneTimeMethod);
        r.setReceiptNumber(req.receiptNumber() != null && !req.receiptNumber().isBlank() ? req.receiptNumber().trim() : null);
        r.setReceiptLast4(req.receiptLast4() != null && !req.receiptLast4().isBlank() ? req.receiptLast4().trim() : null);
        r.setBankId(bankId);
        r.setCardId(cardId);
        r.setCardNumber(cardNumber);
        optionalRecordRepo.save(r);


// También registrar el pago en member_payment_plan / member_payment_record para que aparezca en Check Panel (Conciliación)
// (Debe ser transaccional: si falla, no se registra el pago para evitar inconsistencias con Conciliación)
try {
    syncMemberPaymentForOptionalExcursionPayment(groupId, menuItemId, item, r, methodEnum);
} catch (Exception ex) {
    throw new BadRequestException("No se pudo registrar el pago en conciliación");
}


// NOTA: Para Excursiones (opcionales) el pago del pasajero debe reflejarse en conciliación
// mediante member_payment_* (como Carga manual). No se debe crear un Expense (evita duplicados
// y evita impactar como egreso a proveedor).

// Guardar método seleccionado también en el servicio de excursión (para que no quede vacío en pantallas/resúmenes)
try {
    Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
    if (memberId != null) {
        memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(menuItemId, memberId).ifPresent(svc -> {
            svc.setPaymentMethod(methodEnum);
            try {
                memberOptionalExcursionRepo.save(svc);
            } catch (Exception ignored2) {
            }
        });
    }
} catch (Exception ignored) {
}

        // Devolver el mismo payload que GET /payments
        return getPayments(groupId, menuItemId);
    }

    @Transactional
    public ServicePaymentPlanDto uploadReceipt(Long groupId, Long menuItemId, Long recordId, MultipartFile file) {
        GroupServiceMenuItem menuItem = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
        if (menuItem == null) {
            return uploadOptionalExcursionReceipt(groupId, menuItemId, recordId, file);
        }
        if (recordId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
        }

        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        ServicePaymentRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId));

        if (record.getPlan() == null || plan.getId() == null || !Objects.equals(record.getPlan().getId(), plan.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
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


                    // Also persist receipt data into expenses (to keep financial movements self-contained)
                    try {
                        if (record.getExpenseId() != null) {
                            expenseRepo.findById(record.getExpenseId()).ifPresent(exp -> {
                                exp.setReceiptBlob(record.getReceiptBlob());
                                exp.setReceiptContentType(record.getReceiptContentType());
                                exp.setReceiptFileName(record.getReceiptFileName());
                                if (exp.getReceiptLast4() == null || exp.getReceiptLast4().isBlank()) {
                                    exp.setReceiptLast4(record.getReceiptLast4());
                                }
                                // No copiar receiptNumber si es un batchKey de AÉREOS.
                                exp.setUpdatedAt(Instant.now());
                                expenseRepo.save(exp);
                            });
                        } else {
                            expenseRepo.findFirstByServicePaymentRecordId(record.getId()).ifPresent(exp -> {
                                exp.setReceiptBlob(record.getReceiptBlob());
                                exp.setReceiptContentType(record.getReceiptContentType());
                                exp.setReceiptFileName(record.getReceiptFileName());
                                if (exp.getReceiptLast4() == null || exp.getReceiptLast4().isBlank()) {
                                    exp.setReceiptLast4(record.getReceiptLast4());
                                }
                                if (exp.getReceiptNumber() == null || exp.getReceiptNumber().isBlank()) {
                                    exp.setReceiptNumber(record.getReceiptNumber());
                                }
                                exp.setUpdatedAt(Instant.now());
                                expenseRepo.save(exp);
                            });
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }

        plan.setUpdatedAt(Instant.now());
        planRepo.save(plan);

        // Auto-actualizar estado (PENDIENTE/EMITIDO) según totalCost vs pagos
        refreshAutoOperationStatusIfApplicable(groupId, menuItem);

        List<ServicePaymentRecordDto> records = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId())
                .stream().map(ServicePaymentRecordDto::fromEntity).toList();
        return ServicePaymentPlanDto.fromEntity(plan, records);
    }

    @Transactional
    ServicePaymentPlanDto uploadOptionalExcursionReceipt(Long groupId, Long menuItemId, Long recordId, MultipartFile file) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item no encontrado");
        }
        if (recordId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
        }

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(menuItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        OptionalServicePaymentRecord record = optionalRecordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId));

        if (record.getPlan() == null || plan.getId() == null || !Objects.equals(record.getPlan().getId(), plan.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
        }

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El comprobante es obligatorio");
        }

        try {
            byte[] bytes = file.getBytes();
            if (bytes != null && bytes.length > 0) {
                record.setReceiptBlob(bytes);
                String ct = file.getContentType();
                if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                record.setReceiptContentType(ct);
                record.setReceiptFileName(file.getOriginalFilename());
                optionalRecordRepo.save(record);


// Replicar comprobante en member_payment_record (para que se vea/descargue desde Check Panel)
try {
    syncMemberPaymentReceiptForOptionalExcursionPayment(groupId, menuItemId, item, record);
} catch (Exception ignored3) {
}

                try {
                    plan.setUpdatedAt(Instant.now());
                    optionalPlanRepo.save(plan);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }

        return getPayments(groupId, menuItemId);
    }

    
    @Transactional(readOnly = true)
    public ReceiptDownload downloadReceipt(Long groupId, Long menuItemId, Long recordId) {
        GroupServiceMenuItem mi = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
        if (mi == null) {
            return downloadOptionalExcursionReceipt(groupId, menuItemId, recordId);
        }
        if (recordId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
        }

        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId).orElse(null);

        // 1) Prefer ServicePaymentRecord receipt when applicable
        try {
            ServicePaymentRecord record = recordRepo.findById(recordId).orElse(null);
            if (record != null) {
                boolean ok = true;
                if (plan != null && plan.getId() != null) {
                    ok = record.getPlan() != null && Objects.equals(record.getPlan().getId(), plan.getId());
                }
                if (ok) {
                    byte[] bytes = record.getReceiptBlob();
                    if (bytes != null && bytes.length > 0) {
                        String ct = record.getReceiptContentType();
                        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                        String fn = record.getReceiptFileName();
                        if (fn == null || fn.isBlank()) fn = "comprobante";
                        return new ReceiptDownload(bytes, ct, fn);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2) Fallback: receipt stored directly on Expense (early payments, etc.)
        Expense exp = expenseRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found"));

        if (exp.getGroupId() == null || !Objects.equals(exp.getGroupId(), groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }

        // If expense has menuItemId, enforce match. If it's null, allow only for AEREOS legacy early payments.
        if (exp.getMenuItemId() != null && !Objects.equals(exp.getMenuItemId(), menuItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }
        if (exp.getMenuItemId() == null) {
            ServiceCode sc = (mi.getService() != null) ? mi.getService().getCode() : null;
            if (sc != ServiceCode.AEREOS) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
            }
        }

        byte[] bytes = exp.getReceiptBlob();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }
        String ct = exp.getReceiptContentType();
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
        String fn = exp.getReceiptFileName();
        if (fn == null || fn.isBlank()) fn = "comprobante";
        return new ReceiptDownload(bytes, ct, fn);
    }

    @Transactional(readOnly = true)
    ReceiptDownload downloadOptionalExcursionReceipt(Long groupId, Long menuItemId, Long recordId) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }

        if (recordId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
        }

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(menuItemId).orElse(null);

        OptionalServicePaymentRecord record = optionalRecordRepo.findById(recordId).orElse(null);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        }

        if (plan != null && plan.getId() != null) {
            if (record.getPlan() == null || !Objects.equals(record.getPlan().getId(), plan.getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
            }
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
public void deleteRecord(Long groupId, Long menuItemId, Long recordId) {
    // Acción sensible: requiere autorización si el usuario NO es ADMIN
    authCodeService.requireAuthorizationIfNotAdmin("ADMIN");

    GroupServiceMenuItem menuItem = menuItemRepo.findByIdAndGroupId(menuItemId, groupId).orElse(null);
    if (menuItem == null) {
        deleteOptionalExcursionPaymentRecord(groupId, menuItemId, recordId);
        return;
    }
    if (recordId == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
    }

    // 1) Intentar borrar como ServicePaymentRecord
    ServicePaymentRecord record = recordRepo.findById(recordId).orElse(null);
    if (record != null) {
        ServicePaymentPlan plan = record.getPlan();

        // Capturar antes de borrar
        Long expenseIdToMaybeDelete = record.getExpenseId();
        String receiptNumberForBatch = record.getReceiptNumber();

        Long miId = (plan != null && plan.getMenuItem() != null) ? plan.getMenuItem().getId() : null;
        Long gid = (plan != null && plan.getMenuItem() != null && plan.getMenuItem().getGroup() != null)
                ? plan.getMenuItem().getGroup().getId()
                : null;

        if (!Objects.equals(miId, menuItemId) || !Objects.equals(gid, groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
        }

        ServiceCode sc = (menuItem.getService() != null) ? menuItem.getService().getCode() : null;
        Long airRequestId = record.getMemberId();

        recordRepo.delete(record);

        // También borrar/ajustar el movimiento financiero espejado (Expense) si existe
        try {
            if (sc == ServiceCode.AEREOS) {
                if (expenseIdToMaybeDelete != null) {
                    boolean hasOthers = false;
                    try {
                        if (plan != null && plan.getId() != null) {
                            hasOthers = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                                    .anyMatch(x -> x != null && x.getExpenseId() != null && Objects.equals(x.getExpenseId(), expenseIdToMaybeDelete));
                        }
                    } catch (Exception ignored2) {
                    }

                    if (!hasOthers) {
                        expenseRepo.findById(expenseIdToMaybeDelete).ifPresent(expenseRepo::delete);
                    } else {
                        // Ajustar el monto del expense a la suma de los pagos restantes del mismo batch
                        try {
                            if (plan != null && plan.getId() != null && receiptNumberForBatch != null && !receiptNumberForBatch.isBlank()) {
                                BigDecimal sum = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                                        .filter(Objects::nonNull)
                                        .filter(x -> x.getExpenseId() != null && Objects.equals(x.getExpenseId(), expenseIdToMaybeDelete))
                                        .filter(x -> x.getAmount() != null)
                                        .map(ServicePaymentRecord::getAmount)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                expenseRepo.findById(expenseIdToMaybeDelete).ifPresent(e -> {
                                    try {
                                        e.setAmount(sum);
                                        expenseRepo.save(e);
                                    } catch (Exception ignored3) {
                                    }
                                });
                            }
                        } catch (Exception ignored3) {
                        }
                    }
                } else {
                    // Fallback legacy
                    expenseRepo.findFirstByServicePaymentRecordId(record.getId()).ifPresent(expenseRepo::delete);
                }

                // Total costo = suma de pagos registrados
                try {
                    syncAirTotalCostFromPayments(menuItemId, plan);
                } catch (Exception ignored2) {
                }
            } else {
                expenseRepo.findFirstByServicePaymentRecordId(record.getId()).ifPresent(expenseRepo::delete);
                if (sc == ServiceCode.ALOJAMIENTOS) {
                    try {
                        syncAccommodationTotalCostFromPayments(groupId, menuItemId);
                    } catch (Exception ignored2) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // AÉREOS: si se elimina un pago, el pasajero debe volver a PENDIENTE DE EMISION y reabrirse la emisión.
        if (sc == ServiceCode.AEREOS && airRequestId != null) {
            try {
                revertAirEmissionAfterPaymentDelete(menuItemId, airRequestId);
            } catch (Exception ignored) {
            }
        }

        // Ferry: asegurar que NO queden movimientos legacy/orfandad para el mismo menuItemId
        // (si quedan, bloquean el nuevo pago y dejan el estado en verde incorrectamente).
        try {
            if (sc == ServiceCode.FERRY) {
                for (Expense e : expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId)) {
                    if (e != null) {
                        expenseRepo.delete(e);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Auto-actualizar estado (PENDIENTE/EMITIDO) según totalCost vs pagos
        refreshAutoOperationStatusIfApplicable(groupId, menuItem);

        if (plan != null) {
            plan.setUpdatedAt(Instant.now());
            try {
                planRepo.save(plan);
            } catch (Exception ignored) {
            }
        }
        return;
    }

    // 2) Fallback: pagos basados en Expense (pagos tempranos/legacy, etc.)
    Expense exp = expenseRepo.findById(recordId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId));

    if (exp.getGroupId() == null || !Objects.equals(exp.getGroupId(), groupId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
    }

    ServiceCode sc = (menuItem.getService() != null) ? menuItem.getService().getCode() : null;

    boolean matchesMenuItem = exp.getMenuItemId() != null && Objects.equals(exp.getMenuItemId(), menuItemId);
    boolean legacyAereos = sc == ServiceCode.AEREOS
            && exp.getMenuItemId() == null
            && exp.getNotes() != null
            && exp.getNotes().contains("Pago aéreos temporal");

    if (!matchesMenuItem && !legacyAereos) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
    }

    expenseRepo.delete(exp);

    // AÉREOS (legacy Expense): si se elimina el pago, volver a dejar al pasajero como pendiente y reabrir la emisión.
    if (sc == ServiceCode.AEREOS) {
        try {
            java.util.Set<Long> valid = new java.util.HashSet<>();
            try {
                TravelGroup g = travelGroupRepo.fetchDetail(groupId).orElse(null);
                if (g != null && g.getMembers() != null) {
                    for (TravelRequest tr : g.getMembers()) {
                        if (tr != null && tr.getId() != null) valid.add(tr.getId());
                    }
                }
            } catch (Exception ignored2) {
            }

            Long rid = inferMemberIdFromExpense(exp, valid, new java.util.HashMap<>());
            if (rid != null) {
                revertAirEmissionAfterPaymentDelete(menuItemId, rid);
            }
        } catch (Exception ignored) {
        }
    }

    // ALOJAMIENTOS: total costo = suma de pagos registrados
    try {
        if (sc == ServiceCode.ALOJAMIENTOS) {
            syncAccommodationTotalCostFromPayments(groupId, menuItemId);
        }
    } catch (Exception ignored) {
    }

    // Auto-actualizar estado (PENDIENTE/EMITIDO) según totalCost vs pagos
    refreshAutoOperationStatusIfApplicable(groupId, menuItem);

    // Si existe plan, tocar updatedAt para forzar refresh en front
    try {
        ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId).orElse(null);
        if (plan != null) {
            plan.setUpdatedAt(Instant.now());
            planRepo.save(plan);
        }
    } catch (Exception ignored) {
    }
}

    @Transactional
    void deleteOptionalExcursionPaymentRecord(Long groupId, Long menuItemId, Long recordId) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrow(groupId, menuItemId);
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
        }
        if (recordId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordId is required");
        }

        OptionalServicePaymentRecord record = optionalRecordRepo.findById(recordId).orElse(null);
        if (record == null || record.getPlan() == null || record.getPlan().getMenuItem() == null
                || !Objects.equals(record.getPlan().getMenuItem().getId(), menuItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + recordId);
        }

        OptionalServicePaymentPlan plan = record.getPlan();
        Long optionalPaymentRecordId = record.getId();
        LocalDate paymentDate = record.getPaymentDate();
        BigDecimal paymentAmount = record.getAmount();

        optionalRecordRepo.delete(record);

        // También borrar el Expense espejado (si existe) para limpiar duplicados históricos.
        // Solo se elimina si está explícitamente linkeado a este pago (token optionalPaymentRecordId)
        // para evitar borrar egresos reales a proveedor.
        try {
            String token = optionalPaymentRecordId != null ? ("optionalPaymentRecordId=" + optionalPaymentRecordId) : null;
            List<Expense> expenses = expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId);
            if (expenses != null) {
                for (Expense e : expenses) {
                    if (e == null) continue;
                    String notes = e.getNotes();
                    if (token != null && notes != null && notes.contains(token)) {
                        expenseRepo.delete(e);
                        continue;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // También borrar el MemberPaymentRecord espejado (para que no quede en conciliación de pagos)
        try {
            syncDeleteMemberPaymentForOptionalExcursionPayment(groupId, menuItemId, item, paymentDate, paymentAmount, record.getCurrency(), record.getReceiptLast4());
        } catch (Exception ignored) {
        }

        // Forzar refresh en front
        try {
            if (plan != null) {
                plan.setUpdatedAt(Instant.now());
                optionalPlanRepo.save(plan);
            }
        } catch (Exception ignored) {
        }
    }

    @Transactional(readOnly = true)
    public List<ExpiringReservationDto> findExpiringReservations(int withinHours) {
        return findExpiringReservations(withinHours, null);
    }
    @Transactional(readOnly = true)
    public List<ExpiringReservationDto> findExpiringReservations(int withinHours, String status) {
        java.util.Set<OperationStatusCode> statuses = null;
        if (status != null && !status.isBlank()) {
            String v = status.trim().toUpperCase();
            // Compatibilidad: "SEÑADO" -> SENADO
            v = v.replace("Ñ", "N");
            // "Todos" (FE): devolver Reservado + Señado
            if ("TODOS".equals(v) || "ALL".equals(v)) {
                statuses = null;
            } else if ("SEÑADO".equalsIgnoreCase(status.trim()) || "SENADO".equals(v) || "SENA".equals(v) || "SEÑA".equalsIgnoreCase(status.trim())) {
                statuses = java.util.Set.of(OperationStatusCode.SENADO);
            } else if ("RESERVADO".equals(v)) {
                statuses = java.util.Set.of(OperationStatusCode.RESERVADO);
            } else {
                try {
                    statuses = java.util.Set.of(OperationStatusCode.valueOf(v));
                } catch (Exception ex) {
                    throw new BadRequestException("Estado inválido: " + status);
                }
            }
        }
        return findExpiringReservationsInternal(withinHours, statuses);
    }

    private List<ExpiringReservationDto> findExpiringReservationsInternal(int withinHours, java.util.Set<OperationStatusCode> statuses) {
        if (withinHours <= 0) withinHours = 48;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime limit = now.plusHours(withinHours);
        LocalDate start = now.toLocalDate();
        LocalDate end = limit.toLocalDate();

        List<GroupAccommodationService> candidates;
        if (statuses == null || statuses.isEmpty()) {
            statuses = java.util.Set.of(OperationStatusCode.RESERVADO, OperationStatusCode.SENADO);
        }
        if (statuses.size() == 1) {
            OperationStatusCode st = statuses.iterator().next();
            candidates = accommodationRepo.findExpiringReservationCandidates(st, start, end);
        } else {
            candidates = accommodationRepo.findExpiringReservationCandidatesIn(statuses, start, end);
        }

        boolean opsRestricted = operationsRoleGuardService != null && operationsRoleGuardService.restrictToPaidConfirmed();
        if (opsRestricted) {
            candidates = candidates.stream()
                    .filter(a -> a.getMenuItem() != null && operationsRoleGuardService.isPaidAndConfirmed(a.getMenuItem().getGroup()))
                    .toList();
        }

        java.util.LinkedHashMap<Long, ExpiringReservationDto> out = new java.util.LinkedHashMap<>();
        for (GroupAccommodationService a : candidates) {
            if (a.getReservationDueDate() == null || a.getMenuItem() == null) continue;
            LocalDate dueDate = a.getReservationDueDate();
            // La fecha de vencimiento se carga sin hora;
            // - si vence hoy: asumimos fin de día
            // - si vence en el futuro: usamos inicio del día para no excluir límites de 48hs
            LocalDateTime due = dueDate.isEqual(now.toLocalDate())
                    ? dueDate.atTime(23, 59, 59)
                    : dueDate.atStartOfDay();

            long withinMinutes = (long) withinHours * 60L;
            long minutes = Duration.between(now, due).toMinutes();
            if (minutes < 0 || minutes > withinMinutes) continue;

            GroupServiceMenuItem mi = a.getMenuItem();
            Long groupId = (mi.getGroup() != null) ? mi.getGroup().getId() : null;

            BigDecimal totalAmount = null;
            BigDecimal paidAmount = null;
            ServicePaymentPlan plan = planRepo.findByMenuItemId(mi.getId()).orElse(null);
            if (plan != null) {
                totalAmount = plan.getTotalAmount();
                paidAmount = sumPaid(plan.getId());
            }

            // Monto a mostrar en alertas: total costo del alojamiento (si está cargado)
            BigDecimal totalCost = a.getTotalCost();

            String destination = null;
            if (a.getCity() != null || a.getCountry() != null) {
                String c = a.getCity() != null ? a.getCity().trim() : "";
                String p = a.getCountry() != null ? a.getCountry().trim() : "";
                destination = (c + (c.isEmpty() || p.isEmpty() ? "" : ", ") + p).trim();
                if (destination.isBlank()) destination = null;
            }

            ExpiringReservationDto dto = ExpiringReservationDto.builder()
                    .groupId(groupId)
                    .menuItemId(mi.getId())
                    .serviceCode(mi.getService() != null && mi.getService().getCode() != null ? mi.getService().getCode().name() : null)
                    .displayName(mi.getDisplayName())
                    .statusCode(mi.getOperationStatus() != null ? mi.getOperationStatus().name() : null)
                    .reservationDueDate(a.getReservationDueDate().toString())
                    .destination(destination)
                    .travelDate(a.getCheckInDate() != null ? a.getCheckInDate().toString() : null)
                    .accommodationName(a.getName())
                    .city(a.getCity())
                    .checkInDate(a.getCheckInDate() != null ? a.getCheckInDate().toString() : null)
                    .checkInTime(a.getCheckInTime() != null ? a.getCheckInTime().toString() : null)
                    .totalCost(totalCost)
                    .totalAmount(totalAmount)
                    .paidAmount(paidAmount)
                    .build();

            out.put(mi.getId(), dto);
        }

        // ALOJAMIENTOS: Vencimientos de SEÑA por fecha de cancelación total
        // (se usa para estado SEÑADO en lugar del vencimiento de reserva)
        if (statuses != null && statuses.contains(OperationStatusCode.SENADO)) {
            List<app.coincidir.api.domain.payment.ServicePaymentRecord> cancelCandidates;
            try {
                cancelCandidates = recordRepo.findExpiringTotalCancellationCandidates(OperationStatusCode.SENADO, start, end);
            } catch (Exception ex) {
                cancelCandidates = java.util.Collections.emptyList();
            }

            for (app.coincidir.api.domain.payment.ServicePaymentRecord r : cancelCandidates) {
                if (r == null || r.getPlan() == null || r.getPlan().getMenuItem() == null) continue;
                if (r.getTotalPaymentCancellationDate() == null) continue;

                GroupServiceMenuItem mi = r.getPlan().getMenuItem();
                if (mi.getId() == null) continue;

                if (opsRestricted) {
                    try {
                        if (!operationsRoleGuardService.isPaidAndConfirmed(mi.getGroup())) continue;
                    } catch (Exception ignored) {
                        continue;
                    }
                }

                // Validación adicional: solo aplica a ALOJAMIENTOS
                try {
                    if (mi.getService() == null || mi.getService().getCode() != ServiceCode.ALOJAMIENTOS) continue;
                } catch (Exception ignored) {
                    continue;
                }

                LocalDate dueDate = r.getTotalPaymentCancellationDate();
                LocalDateTime due = dueDate.isEqual(now.toLocalDate())
                        ? dueDate.atTime(23, 59, 59)
                        : dueDate.atStartOfDay();

                long withinMinutes = (long) withinHours * 60L;
                long minutes = Duration.between(now, due).toMinutes();
                if (minutes < 0 || minutes > withinMinutes) continue;

                GroupAccommodationService a = null;
                try {
                    a = accommodationRepo.findByMenuItemId(mi.getId()).orElse(null);
                } catch (Exception ignored) {
                }
                if (a == null) continue;

                Long groupId = (mi.getGroup() != null) ? mi.getGroup().getId() : null;

                BigDecimal totalAmount = null;
                BigDecimal paidAmount = null;
                try {
                    app.coincidir.api.domain.payment.ServicePaymentPlan plan = planRepo.findByMenuItemId(mi.getId()).orElse(null);
                    if (plan != null) {
                        totalAmount = plan.getTotalAmount();
                        paidAmount = sumPaid(plan.getId());
                    }
                } catch (Exception ignored) {
                }

                BigDecimal totalCost = a.getTotalCost();

                String destination = null;
                if (a.getCity() != null || a.getCountry() != null) {
                    String c = a.getCity() != null ? a.getCity().trim() : "";
                    String p = a.getCountry() != null ? a.getCountry().trim() : "";
                    destination = (c + (c.isEmpty() || p.isEmpty() ? "" : ", ") + p).trim();
                    if (destination.isBlank()) destination = null;
                }

                ExpiringReservationDto dto = ExpiringReservationDto.builder()
                        .groupId(groupId)
                        .menuItemId(mi.getId())
                        .serviceCode(mi.getService() != null && mi.getService().getCode() != null ? mi.getService().getCode().name() : null)
                        .displayName(mi.getDisplayName())
                        .statusCode(mi.getOperationStatus() != null ? mi.getOperationStatus().name() : null)
                        .reservationDueDate(dueDate.toString())
                        .destination(destination)
                        .travelDate(a.getCheckInDate() != null ? a.getCheckInDate().toString() : null)
                        .accommodationName(a.getName())
                        .city(a.getCity())
                        .checkInDate(a.getCheckInDate() != null ? a.getCheckInDate().toString() : null)
                        .checkInTime(a.getCheckInTime() != null ? a.getCheckInTime().toString() : null)
                        .totalCost(totalCost)
                        .totalAmount(totalAmount)
                        .paidAmount(paidAmount)
                        .build();

                // Override del vencimiento de RESERVA por el vencimiento de CANCELACIÓN TOTAL
                out.put(mi.getId(), dto);
            }
        }

        return new java.util.ArrayList<>(out.values());
    }


    private static final Pattern PAX_ID_PATTERN = Pattern.compile("(?:(?:pasajeroId)|(?:memberId)|(?:requestId))\s*=\s*(\\d+)");

    private static Long inferMemberIdFromExpense(Expense e, Set<Long> validMemberIds, Map<String, Long> membersByNormName) {
        if (e == null) return null;

        String notes = e.getNotes();
        if (notes != null && !notes.isBlank()) {
            Matcher m = PAX_ID_PATTERN.matcher(notes);
            if (m.find()) {
                try {
                    Long id = Long.valueOf(m.group(1));
                    if (validMemberIds == null || validMemberIds.contains(id)) return id;
                } catch (Exception ignored) {
                }
            }
        }

        String pax = extractPassengerNameFromConcept(e.getConcept());
        if (pax != null) {
            String nn = normalizeName(pax);
            if (nn != null && membersByNormName != null) {
                Long id = membersByNormName.get(nn);
                if (id != null && (validMemberIds == null || validMemberIds.contains(id))) return id;
            }
        }

        return null;
    }

    private static String extractPassengerNameFromConcept(String concept) {
        if (concept == null) return null;
        String s = concept.trim();
        if (s.isEmpty()) return null;

        String[] parts = s.split(" - ");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1];
            if (last == null) return null;
            last = last.trim();
            return last.isEmpty() ? null : last;
        }
        return null;
    }

    private static String normalizeName(String name) {
        if (name == null) return null;
        String s = name.trim().toLowerCase();
        if (s.isEmpty()) return null;
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private static String extractReservationCodeFromNotes(String notes) {
        if (notes == null || notes.isBlank()) return null;

        int idx = notes.indexOf("reservationCode=");
        if (idx < 0) idx = notes.indexOf("reservation_code=");
        if (idx < 0) return null;

        String tail = notes.substring(idx);
        int eq = tail.indexOf('=');
        if (eq < 0) return null;

        String val = tail.substring(eq + 1).trim();
        int sp = val.indexOf(' ');
        if (sp > 0) val = val.substring(0, sp);

        return val.isBlank() ? null : val;
    }

    private GroupServiceMenuItem getMenuItemOrThrow(Long groupId, Long menuItemId) {
        return menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new NotFoundException("Menu item no encontrado"));
    }

    private MemberOptionalServiceMenuItem getOptionalMenuItemOrThrow(Long groupId, Long menuItemId) {
        MemberOptionalServiceMenuItem item = memberOptionalMenuItemRepo.findById(menuItemId)
                .orElseThrow(() -> new NotFoundException("Menu item no encontrado"));

        Long gid = (item.getMember() != null && item.getMember().getGroup() != null)
                ? item.getMember().getGroup().getId()
                : null;

        if (gid == null || !Objects.equals(gid, groupId)) {
            throw new NotFoundException("Menu item no encontrado");
        }
        return item;
    }

    private BigDecimal sumPaid(Long planId) {
        return recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(planId)
                .stream()
                .map(ServicePaymentRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void revertAirEmissionAfterPaymentDelete(Long menuItemId, Long requestId) {
        if (menuItemId == null || requestId == null) return;

        // 1) Revertir flag de emisión para que vuelva a figurar como pendiente
        try {
            memberEmisionRepo.findTopByRequestIdOrderByCreatedAtDesc(requestId).ifPresent(em -> {
                try {
                    em.setEmitted(false);
                    em.setEmittedAt(null);
                    em.setStatus("PENDIENTE");
                    memberEmisionRepo.save(em);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }

        // 2) Limpiar código de reserva a nivel miembro para que el servicio NO quede como EMITIDO por arrastre
        try {
            memberAirRepo.findByMenuItemIdAndMemberId(menuItemId, requestId).ifPresent(mas -> {
                try {
                    mas.setReservationCode(null);
                    memberAirRepo.save(mas);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }


    private void syncAirReservationCode(Long groupId, GroupServiceMenuItem menuItem, Long memberId, String reservationCode) {
        if (groupId == null || menuItem == null || menuItem.getId() == null || memberId == null) return;
        if (reservationCode == null) return;
        String rc = reservationCode.trim();
        if (rc.isBlank()) return;

        // 1) Sincronizar a nivel de servicio de grupo SOLO si es un grupo de 1 pasajero.
        // En grupales (2+), setear el código a nivel grupo puede hacer que la UI marque
        // incorrectamente como emitidos a TODOS cuando solo se emitió 1.
        try {
            long pax = 0L;
            try {
                TravelGroup g = travelGroupRepo.fetchDetail(groupId).orElse(null);
                if (g != null && g.getMembers() != null) {
                    pax = g.getMembers().stream().filter(Objects::nonNull).count();
                }
            } catch (Exception ignored2) {
                pax = 0L;
            }

            if (pax <= 1L) {
                groupAirRepo.findByMenuItemId(menuItem.getId()).ifPresent(gs -> {
                    String existing = gs.getReservationCode();
                    if (existing == null || existing.isBlank()) {
                        gs.setReservationCode(rc);
                        groupAirRepo.save(gs);
                    }
                });
            }
        } catch (Exception ignored) {
        }

        // 2) Sincronizar a nivel miembro (crear override si no existe)
        try {
            MemberAirService mas = memberAirRepo.findByMenuItemIdAndMemberId(menuItem.getId(), memberId).orElse(null);
            if (mas == null) {
                TravelGroup g = null;
                try {
                    g = travelGroupRepo.fetchDetail(groupId).orElse(null);
                } catch (Exception ignored) {
                }
                TravelRequest tr = null;
                if (g != null && g.getMembers() != null) {
                    for (TravelRequest t : g.getMembers()) {
                        if (t != null && t.getId() != null && t.getId().equals(memberId)) {
                            tr = t;
                            break;
                        }
                    }
                }
                if (tr == null) return;
                mas = new MemberAirService();
                mas.setMenuItem(menuItem);
                mas.setMember(tr);
                mas.setOverridden(true);
            }
            mas.setReservationCode(rc);
            memberAirRepo.save(mas);
        } catch (Exception ignored) {
        }
    }



/* -------------------- Optional Excursiones -> Member Payments (Check Panel) -------------------- */

private static String normalizeLast4(String raw) {
    if (raw == null) return null;
    String t = raw.trim();
    if (t.isEmpty()) return null;
    // tomar últimos 4 dígitos si viene con máscara o texto
    String digits = t.replaceAll("[^0-9]", "");
    if (digits.length() >= 4) {
        return digits.substring(digits.length() - 4);
    }
    // si viene exacto pero no numérico, no sirve
    return null;
}

private void syncMemberPaymentForOptionalExcursionPayment(
        Long groupId,
        Long menuItemId,
        MemberOptionalServiceMenuItem item,
        OptionalServicePaymentRecord optionalRecord,
        PaymentOneTimeMethod methodEnum
) {
    if (groupId == null || menuItemId == null || item == null || optionalRecord == null) return;
    Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
    if (memberId == null) return;
    if (optionalRecord.getAmount() == null || optionalRecord.getAmount().compareTo(BigDecimal.ZERO) <= 0) return;
    if (optionalRecord.getPaymentDate() == null) return;

    // Para conciliación (Check Panel), los pagos de miembros se registran en ARS.
    final String currency = "ARS";

    String last4 = normalizeLast4(optionalRecord.getReceiptLast4());
    if (last4 == null) {
        // fallback: si hay cardNumber o receiptNumber, intentar derivar
        last4 = normalizeLast4(optionalRecord.getCardNumber());
        if (last4 == null) last4 = normalizeLast4(optionalRecord.getReceiptNumber());
    }
    if (last4 == null) last4 = "0000";

    boolean createdPlanHere = false;
    MemberPaymentPlan plan = memberPaymentPlanRepo.findByGroupIdAndMemberId(groupId, memberId).orElse(null);

    if (plan == null) {
        // Si venía sin grupo (carga manual), intentar materializarlo como plan del grupo.
        // Si falla (por ejemplo, por unique constraint), caer al plan del grupo o crear uno nuevo.
        MemberPaymentPlan ungrouped = memberPaymentPlanRepo.findByGroupIdIsNullAndMemberId(memberId).orElse(null);
        if (ungrouped != null) {
            ungrouped.setGroupId(groupId);
            try {
                plan = memberPaymentPlanRepo.save(ungrouped);
            } catch (Exception ignored) {
                plan = memberPaymentPlanRepo.findByGroupIdAndMemberId(groupId, memberId).orElse(null);
            }
        }
    }

    if (plan == null) {
        // Plan placeholder mínimo para permitir registrar el movimiento en conciliación
        PaymentOneTimeMethod m = methodEnum;
        if (m == null) {
            try {
                String raw = optionalRecord.getOneTimeMethod();
                if (raw != null && !raw.isBlank()) m = parseOneTimeMethod(raw);
            } catch (Exception ignored) {
            }
        }
        if (m == null) m = PaymentOneTimeMethod.TRANSFERENCIA;

        plan = MemberPaymentPlan.builder()
                .groupId(groupId)
                .memberId(memberId)
                .planType(PaymentPlanType.ONE_TIME)
                .oneTimeMethod(m)
                .totalAmount(optionalRecord.getAmount())
                .receiptLast4(last4)
                .notes("AUTO: Pago adicional Excursiones (Operations). MenuItemId=" + menuItemId)
                .build();

        createdPlanHere = true;

        // Cuota 1 (ONE_TIME)
        try {
            MemberPaymentInstallment inst = MemberPaymentInstallment.builder()
                    .plan(plan)
                    .installmentNumber(1)
                    .dueDate(optionalRecord.getPaymentDate())
                    .amount(optionalRecord.getAmount())
                    .status(InstallmentStatus.PAID)
                    .paidDate(optionalRecord.getPaymentDate())
                    .build();
            plan.getInstallments().add(inst);
        } catch (Exception ignored) {
        }

        try {
            plan = memberPaymentPlanRepo.save(plan);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo crear member_payment_plan");
        }
    }

    if (plan.getId() == null) {
        throw new IllegalStateException("No se pudo persistir member_payment_plan");
    }

    // Asegurar que el plan quede asociado al grupo (si por algún motivo quedó null)
    if (plan.getGroupId() == null) {
        plan.setGroupId(groupId);
        plan = memberPaymentPlanRepo.save(plan);
    }

    // Asegurar moneda ARS en el plan (por consistencia con conciliación)
    try {
        if (plan.getCurrency() == null || !plan.getCurrency().equalsIgnoreCase("ARS")) {
            plan.setCurrency("ARS");
            plan = memberPaymentPlanRepo.save(plan);
        }
    } catch (Exception ignored) {
    }

    // Asegurar existencia de cuota para completar installment_number
    int installmentNumber = 1;
    try {
        if (createdPlanHere) {
            installmentNumber = 1;
        } else {
            int max = 0;
            if (plan.getInstallments() != null) {
                for (MemberPaymentInstallment i : plan.getInstallments()) {
                    if (i == null || i.getInstallmentNumber() == null) continue;
                    max = Math.max(max, i.getInstallmentNumber());
                }
            }

            if (max >= 1) {
                installmentNumber = max + 1;
            }

            boolean existsInst = false;
            if (plan.getInstallments() != null) {
                for (MemberPaymentInstallment i : plan.getInstallments()) {
                    if (i == null) continue;
                    if (i.getInstallmentNumber() != null && i.getInstallmentNumber() == installmentNumber) {
                        existsInst = true;
                        break;
                    }
                }
            }
            if (!existsInst) {
                MemberPaymentInstallment inst = MemberPaymentInstallment.builder()
                        .plan(plan)
                        .installmentNumber(installmentNumber)
                        .dueDate(optionalRecord.getPaymentDate())
                        .amount(optionalRecord.getAmount())
                        .status(InstallmentStatus.PAID)
                        .paidDate(optionalRecord.getPaymentDate())
                        .build();
                plan.getInstallments().add(inst);
                plan = memberPaymentPlanRepo.save(plan);
            }
        }
    } catch (Exception ignored) {
        installmentNumber = 1;
    }

    // Evitar duplicados (defensivo)
    try {
        List<MemberPaymentRecord> existing = memberPaymentRecordRepo
                .findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId);
        if (existing != null) {
            for (MemberPaymentRecord mr : existing) {
                if (mr == null || mr.getId() == null) continue;
                try {
                    if (mr.getPlan() != null && plan.getId() != null && mr.getPlan().getId() != null
                            && !Objects.equals(mr.getPlan().getId(), plan.getId())) {
                        continue;
                    }
                } catch (Exception ignored2) {
                }
                if (mr.getPaymentDate() != null && mr.getPaymentDate().equals(optionalRecord.getPaymentDate())
                        && mr.getAmount() != null && mr.getAmount().compareTo(optionalRecord.getAmount()) == 0
                        && mr.getCurrency() != null && mr.getCurrency().equalsIgnoreCase(currency)
                        && mr.getReceiptLast4() != null && mr.getReceiptLast4().equals(last4)) {
                    return;
                }
            }
        }
    } catch (Exception ignored) {
    }

    MemberPaymentRecord rec = MemberPaymentRecord.builder()
            .plan(plan)
            .groupId(groupId)
            .memberId(memberId)
            .installmentNumber(installmentNumber)
            .amount(optionalRecord.getAmount())
            .currency(currency)
            .paymentDate(optionalRecord.getPaymentDate())
            .receiptLast4(last4)
            .receiptBlob(optionalRecord.getReceiptBlob())
            .receiptContentType(optionalRecord.getReceiptContentType())
            .receiptFileName(optionalRecord.getReceiptFileName())
            .build();

    try {
        memberPaymentRecordRepo.save(rec);
    } catch (Exception ex) {
        throw new IllegalStateException("No se pudo crear member_payment_record");
    }
}

private void syncMemberPaymentReceiptForOptionalExcursionPayment(
        Long groupId,
        Long menuItemId,
        MemberOptionalServiceMenuItem item,
        OptionalServicePaymentRecord optionalRecord
) {
    if (groupId == null || menuItemId == null || item == null || optionalRecord == null) return;
    Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
    if (memberId == null) return;
    if (optionalRecord.getReceiptBlob() == null || optionalRecord.getReceiptBlob().length == 0) return;
    if (optionalRecord.getPaymentDate() == null || optionalRecord.getAmount() == null) return;

    // Para conciliación (Check Panel), los pagos de miembros se registran en ARS.
    final String currency = "ARS";
    String last4 = normalizeLast4(optionalRecord.getReceiptLast4());
    if (last4 == null) {
        last4 = normalizeLast4(optionalRecord.getCardNumber());
        if (last4 == null) last4 = normalizeLast4(optionalRecord.getReceiptNumber());
    }
    if (last4 == null) last4 = "0000";

    List<MemberPaymentRecord> records;
    try {
        records = memberPaymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId);
    } catch (Exception e) {
        return;
    }
    if (records == null || records.isEmpty()) return;

    MemberPaymentRecord target = null;
    for (MemberPaymentRecord mr : records) {
        if (mr == null || mr.getId() == null) continue;
        if (mr.getPaymentDate() == null || !mr.getPaymentDate().equals(optionalRecord.getPaymentDate())) continue;
        if (mr.getAmount() == null || mr.getAmount().compareTo(optionalRecord.getAmount()) != 0) continue;
        if (mr.getCurrency() == null || !mr.getCurrency().equalsIgnoreCase(currency)) continue;
        if (mr.getReceiptLast4() == null || !mr.getReceiptLast4().equals(last4)) continue;
        // Preferir el que aún no tenga comprobante
        if (mr.getReceiptBlob() == null || mr.getReceiptBlob().length == 0) {
            target = mr;
            break;
        }
        if (target == null) target = mr;
    }
    if (target == null) return;

    target.setReceiptBlob(optionalRecord.getReceiptBlob());
    if (optionalRecord.getReceiptContentType() != null && !optionalRecord.getReceiptContentType().isBlank()) {
        target.setReceiptContentType(optionalRecord.getReceiptContentType());
    }
    if (optionalRecord.getReceiptFileName() != null && !optionalRecord.getReceiptFileName().isBlank()) {
        target.setReceiptFileName(optionalRecord.getReceiptFileName());
    }

    try {
        memberPaymentRecordRepo.save(target);
    } catch (Exception ignored) {
    }
}

private void syncDeleteMemberPaymentForOptionalExcursionPayment(
        Long groupId,
        Long menuItemId,
        MemberOptionalServiceMenuItem item,
        LocalDate paymentDate,
        BigDecimal paymentAmount,
        String currencyRaw,
        String receiptLast4Raw
) {
    if (groupId == null || menuItemId == null || item == null) return;
    Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
    if (memberId == null) return;
    if (paymentDate == null || paymentAmount == null) return;
    // Member payments (conciliación) se guardan en ARS. Mantener fallback por si hay registros legacy.
    String currencyFallback = (currencyRaw == null || currencyRaw.isBlank()) ? null : currencyRaw.trim();
    final String currency = "ARS";
    String last4 = normalizeLast4(receiptLast4Raw);
    if (last4 == null) last4 = "0000";

    List<MemberPaymentRecord> records = null;
    try {
        records = memberPaymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId);
    } catch (Exception ignored) {
    }
    if (records == null || records.isEmpty()) return;

    for (MemberPaymentRecord mr : records) {
        if (mr == null || mr.getId() == null) continue;
        if (mr.getPaymentDate() == null || !mr.getPaymentDate().equals(paymentDate)) continue;
        if (mr.getAmount() == null || mr.getAmount().compareTo(paymentAmount) != 0) continue;
        if (mr.getCurrency() == null) continue;
        boolean currencyOk = mr.getCurrency().equalsIgnoreCase(currency)
                || (currencyFallback != null && mr.getCurrency().equalsIgnoreCase(currencyFallback));
        if (!currencyOk) continue;
        if (mr.getReceiptLast4() == null || !mr.getReceiptLast4().equals(last4)) continue;
        Integer instNum = mr.getInstallmentNumber();
        MemberPaymentPlan plan = null;
        try { plan = mr.getPlan(); } catch (Exception ignored) {}

        try {
            memberPaymentRecordRepo.delete(mr);
        } catch (Exception ignored) {
        }

        // También borrar la cuota asociada (si existe) para no dejar installments huérfanas.
        if (plan != null && instNum != null) {
            try {
                if (plan.getInstallments() != null) {
                    plan.getInstallments().removeIf(i -> i != null
                            && i.getInstallmentNumber() != null
                            && i.getInstallmentNumber().equals(instNum)
                            && (i.getAmount() == null || i.getAmount().compareTo(paymentAmount) == 0)
                            && (i.getPaidDate() == null || i.getPaidDate().equals(paymentDate))
                    );
                    memberPaymentPlanRepo.save(plan);
                }
            } catch (Exception ignored) {
            }
        }
        break;
    }
}


private void createExpenseForOptionalExcursionPayment(Long groupId, Long menuItemId, MemberOptionalServiceMenuItem item, OptionalServicePaymentRecord record, PaymentOneTimeMethod methodEnum) {
    if (groupId == null || menuItemId == null || item == null || record == null) return;
    try {
        Expense e = new Expense();
        e.setDate(record.getPaymentDate());
        e.setType(ExpenseType.PROVEEDOR);
        e.setStatus(ExpenseStatus.PAGADO);
        // En listado de gastos se pide mostrar "$" (sin "US$").
        // Se fuerza ARS en expenses para el símbolo; el detalle de moneda original queda en notes.
        String originalCurrency = record.getCurrency();
        e.setCurrency("ARS");
        e.setAmount(record.getAmount());
        e.setPaymentMethod(record.getOneTimeMethod());
        e.setReceiptNumber(record.getReceiptNumber());
        e.setReceiptLast4(record.getReceiptLast4());
        e.setMenuItemId(menuItemId);
        e.setGroupId(groupId);
        e.setCategory("OPERACIONES");

        String fullName = (item.getMember() != null && item.getMember().getName() != null) ? item.getMember().getName().trim() : "";
        if (fullName.isBlank()) fullName = "Sin nombre";
        e.setConcept("Adicional | Excursion - " + fullName);

        // Intentar setear prestador si ya está cargado en el servicio de excursión
        try {
            Long memberId = (item.getMember() != null) ? item.getMember().getId() : null;
            if (memberId != null) {
                memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(menuItemId, memberId).ifPresent(svc -> {
                    if (svc.getPrestador() != null) {
                        e.setProvider(svc.getPrestador());
                    }
                });
            }
        } catch (Exception ignored) {
        }

        StringBuilder notes = new StringBuilder();
        notes.append("Pago desde Operations. GroupId=").append(groupId).append(" MenuItemId=").append(menuItemId)
                .append(" optionalExcursion=true");
        if (item.getMember() != null && item.getMember().getId() != null) {
            notes.append(" memberId=").append(item.getMember().getId());
        }
        if (record.getId() != null) {
            notes.append(" optionalPaymentRecordId=").append(record.getId());
        }
        if (methodEnum != null) {
            notes.append(" method=").append(methodEnum.name());
        }
        if (originalCurrency != null && !originalCurrency.isBlank()) {
            notes.append(" originalCurrency=").append(originalCurrency.trim());
        }
        e.setNotes(notes.toString());

        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);

        expenseRepo.save(e);
    } catch (Exception ignored) {
    }
}

private void syncOptionalExcursionExpenseReceipt(Long groupId, Long menuItemId, MemberOptionalServiceMenuItem item, OptionalServicePaymentRecord record) {
    if (groupId == null || menuItemId == null || item == null || record == null) return;
    try {
        String fullName = (item.getMember() != null && item.getMember().getName() != null) ? item.getMember().getName().trim() : "";
        if (fullName.isBlank()) fullName = "Sin nombre";
        String expectedConcept = "Adicional | Excursion - " + fullName;

        List<Expense> expenses = expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId);
        if (expenses == null || expenses.isEmpty()) return;

        Expense target = null;
        for (Expense e : expenses) {
            if (e == null) continue;
            if (e.getDate() != null && record.getPaymentDate() != null && !e.getDate().equals(record.getPaymentDate())) continue;
            if (e.getAmount() != null && record.getAmount() != null && e.getAmount().compareTo(record.getAmount()) != 0) continue;
            if (e.getConcept() == null || !e.getConcept().equals(expectedConcept)) continue;
            target = e;
            break;
        }
        if (target == null) return;

        byte[] bytes = record.getReceiptBlob();
        if (bytes != null && bytes.length > 0) {
            target.setReceiptBlob(bytes);
            if (record.getReceiptContentType() != null && !record.getReceiptContentType().isBlank()) {
                target.setReceiptContentType(record.getReceiptContentType());
            }
            if (record.getReceiptFileName() != null && !record.getReceiptFileName().isBlank()) {
                target.setReceiptFileName(record.getReceiptFileName());
            }
        }
        if (target.getPaymentMethod() == null || target.getPaymentMethod().isBlank()) {
            target.setPaymentMethod(record.getOneTimeMethod());
        }
        if (target.getReceiptLast4() == null || target.getReceiptLast4().isBlank()) {
            target.setReceiptLast4(record.getReceiptLast4());
        }
        if (target.getReceiptNumber() == null || target.getReceiptNumber().isBlank()) {
            target.setReceiptNumber(record.getReceiptNumber());
        }
        target.setUpdatedAt(Instant.now());
        expenseRepo.save(target);
    } catch (Exception ignored) {
    }
}

    private void createExpenseForPayment(GroupServiceMenuItem menuItem, ServicePaymentPlan plan, ServicePaymentRecord r, Long memberId, String passengerFullName, String reservationCode) {
        try {
            Expense e = new Expense();
            e.setDate(r.getPaymentDate());
            e.setType(ExpenseType.PROVEEDOR);
            e.setStatus(ExpenseStatus.PAGADO);
            e.setCurrency(r.getCurrency());
            e.setAmount(r.getAmount());
            e.setPaymentMethod(r.getOneTimeMethod());
            e.setReceiptNumber(r.getReceiptNumber());
            e.setReceiptLast4(r.getReceiptLast4());
            e.setReceiptBlob(r.getReceiptBlob());
            e.setReceiptContentType(r.getReceiptContentType());
            e.setReceiptFileName(r.getReceiptFileName());
            e.setMenuItemId(menuItem.getId());
            e.setServicePaymentRecordId(r.getId());
            e.setCategory("OPERACIONES");
            e.setConcept(buildExpenseConcept(menuItem, plan, passengerFullName));
            Long gid = (menuItem.getGroup() != null) ? menuItem.getGroup().getId() : null;
            e.setGroupId(gid);
            StringBuilder notes = new StringBuilder();
            notes.append("Pago desde Operations. GroupId=").append(gid != null ? gid : "-")
                    .append(" MenuItemId=").append(menuItem.getId());
            if (memberId != null) {
                notes.append(" pasajeroId=").append(memberId);
            }
            if (reservationCode != null && !reservationCode.isBlank()) {
                notes.append(" reservationCode=").append(reservationCode.trim());
            }
            e.setNotes(notes.toString());
            Instant now = Instant.now();
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            expenseRepo.save(e);
        } catch (Exception ex) {
            // No bloquear el registro del pago si falla el impacto en gastos
        }
    }


    private void refreshAutoOperationStatusIfApplicable(Long groupId, GroupServiceMenuItem menuItem) {
        if (menuItem == null || groupId == null) return;
        ServiceCode sc = (menuItem.getService() != null) ? menuItem.getService().getCode() : null;
        if (sc != ServiceCode.AEREOS && sc != ServiceCode.FERRY
                && sc != ServiceCode.TRASLADOS && sc != ServiceCode.TRASLADOS_DESTINO
                && sc != ServiceCode.ADICIONALES) return;

        BigDecimal totalCost = null;
        boolean transfersDataComplete = true;
        try {
            if (sc == ServiceCode.AEREOS) {
                totalCost = groupAirRepo.findByMenuItemId(menuItem.getId())
                        .map(GroupAirService::getTotalCost)
                        .orElse(null);
            } else if (sc == ServiceCode.FERRY) {
                totalCost = groupFerryRepo.findByMenuItemId(menuItem.getId())
                        .map(GroupFerryService::getTotalCost)
                        .orElse(null);
            } else if (sc == ServiceCode.TRASLADOS) {
                GroupTransferService t = groupTransferRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (t != null) {
                    totalCost = t.getTotalCost();
                    transfersDataComplete = isTransfersDataComplete(t.getProvider(), t.getPickupPlace(), t.getDestinationPlace(), t.getReservationCode(), t.getDepartureDate(), t.getDepartureTime(), t.getReturnDate(), t.getReturnTime());
                } else {
                    transfersDataComplete = false;
                }
            } else if (sc == ServiceCode.TRASLADOS_DESTINO) {
                GroupDestinationTransferService t = groupDestinationTransferRepo.findByMenuItemId(menuItem.getId()).orElse(null);
                if (t != null) {
                    totalCost = t.getTotalCost();
                    boolean base = isTransfersDataComplete(t.getProvider(), t.getPickupPlace(), t.getDestinationPlace(), t.getReservationCode(), t.getDepartureDate(), t.getDepartureTime(), t.getReturnDate(), t.getReturnTime());
                    boolean countryOk = t.getCountry() == null || !t.getCountry().trim().isBlank();
                    boolean cityOk = t.getCity() == null || !t.getCity().trim().isBlank();
                    transfersDataComplete = base && countryOk && cityOk;
                } else {
                    transfersDataComplete = false;
                }
            }
        } catch (Exception ignored) {
        }

        // Sin costo real cargado, el estado debe quedar PENDIENTE
        OperationStatusCode desired = OperationStatusCode.PENDIENTE;

        BigDecimal paid = sumPaidFromExpenses(groupId, menuItem.getId(), sc);
        if (paid == null) paid = BigDecimal.ZERO;

        if (sc == ServiceCode.AEREOS) {
            // AÉREOS (GRUPALES): no marcar el servicio como EMITIDO por tener una sola reserva/pago.
            // Debe considerarse EMITIDO solo cuando TODOS los pasajeros del grupo tienen
            // reserva/código a nivel miembro (o están marcados emitted en member_emision).

            // 1) Miembros del grupo
            List<Long> memberIds = new ArrayList<>();
            try {
                TravelGroup g = travelGroupRepo.fetchDetail(groupId).orElse(null);
                if (g != null && g.getMembers() != null) {
                    for (TravelRequest tr : g.getMembers()) {
                        if (tr != null && tr.getId() != null) memberIds.add(tr.getId());
                    }
                }
            } catch (Exception ignored) {
            }

            // 2) Emitidos por código de reserva (a nivel miembro)
            Set<Long> emittedByReservation = new HashSet<>();
            try {
                for (MemberAirService mas : memberAirRepo.findByMenuItemId(menuItem.getId())) {
                    if (mas == null || mas.getMember() == null || mas.getMember().getId() == null) continue;
                    String rc = mas.getReservationCode();
                    if (rc != null && !rc.trim().isBlank()) {
                        emittedByReservation.add(mas.getMember().getId());
                    }
                }
            } catch (Exception ignored) {
            }

            // 3) Emitidos por flag (member_emision.emitted = 1)
            Set<Long> emittedByFlag = new HashSet<>();
            try {
                if (!memberIds.isEmpty()) {
                    List<MemberEmision> ems = memberEmisionRepo.findByRequestIdInAndEmittedIsTrue(memberIds);
                    for (MemberEmision me : ems) {
                        if (me == null || me.getRequestId() == null) continue;
                        emittedByFlag.add(me.getRequestId());
                    }
                }
            } catch (Exception ignored) {
            }

            boolean allEmitted = false;
            if (!memberIds.isEmpty()) {
                allEmitted = true;
                for (Long mid : memberIds) {
                    if (mid == null) continue;
                    if (emittedByReservation.contains(mid) || emittedByFlag.contains(mid)) continue;
                    allEmitted = false;
                    break;
                }
            }

            desired = (paid.compareTo(BigDecimal.ZERO) > 0 && allEmitted)
                    ? OperationStatusCode.EMITIDO
                    : OperationStatusCode.PENDIENTE;
        } else if (sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO) {
            if (totalCost != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal remaining = totalCost.subtract(paid);
                if (transfersDataComplete && paid.compareTo(BigDecimal.ZERO) > 0) {
                    desired = (remaining.compareTo(BigDecimal.ZERO) <= 0)
                            ? OperationStatusCode.PAGADO
                            : OperationStatusCode.SENADO;
                }
            }
        } else if (sc == ServiceCode.ADICIONALES) {
            // ADICIONALES: PAGADO cuando hay al menos un pago registrado
            if (paid.compareTo(BigDecimal.ZERO) > 0) {
                desired = OperationStatusCode.PAGADO;
            }
        } else {
            // FERRY
            if (totalCost != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal remaining = totalCost.subtract(paid);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    desired = OperationStatusCode.EMITIDO;
                }
            }
        }

        if (menuItem.getOperationStatus() == null || menuItem.getOperationStatus() != desired) {
            menuItem.setOperationStatus(desired);
            menuItem.setOperationStatusUpdatedAt(Instant.now());
            try {
                menuItemRepo.save(menuItem);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isTransfersDataComplete(
            String provider,
            String pickupPlace,
            String destinationPlace,
            String reservationCode,
            LocalDate departureDate,
            java.time.LocalTime departureTime,
            LocalDate returnDate,
            java.time.LocalTime returnTime
    ) {
        if (provider == null || provider.trim().isBlank()) return false;
        if (pickupPlace == null || pickupPlace.trim().isBlank()) return false;
        if (destinationPlace == null || destinationPlace.trim().isBlank()) return false;
        if (reservationCode == null || reservationCode.trim().isBlank()) return false;
        if (departureDate == null) return false;
        if (departureTime == null) return false;

        boolean hasReturnDate = returnDate != null;
        boolean hasReturnTime = returnTime != null;
        if (hasReturnDate ^ hasReturnTime) return false;

        return true;
    }

    private BigDecimal sumPaidFromExpenses(Long groupId, Long menuItemId, ServiceCode sc) {
        // Ferry: sumar por ServicePaymentRecord para evitar que Expenses legacy/orfandad afecten el estado.
        if (sc == ServiceCode.FERRY) {
            try {
                ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId).orElse(null);
                if (plan != null && plan.getId() != null) {
                    return recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                            .map(ServicePaymentRecord::getAmount)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                }
            } catch (Exception ignored) {
            }
        }

        LinkedHashMap<Long, Expense> uniq = new LinkedHashMap<>();
        try {
            for (Expense e : expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId)) {
                if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
            }
        } catch (Exception ignored) {
        }
        if (sc == ServiceCode.AEREOS) {
            try {
                for (Expense e : expenseRepo.findAllByGroupIdAndNotesContaining(groupId, "Pago aéreos temporal")) {
                    if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
                }
            } catch (Exception ignored) {
            }
        }
        return uniq.values().stream()
                .filter(Objects::nonNull)
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String buildExpenseConcept(GroupServiceMenuItem menuItem, ServicePaymentPlan plan, String passengerFullName) {
        ServiceCode sc = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        if (sc == ServiceCode.ALOJAMIENTOS) {
            return plan.getPaymentForm() == ServicePaymentForm.SENA ? "Pago Seña Alojamiento" : "Pago Total Alojamiento";
        }
        String label = menuItem.getDisplayName();
        if (label == null || label.isBlank()) {
            label = (sc != null) ? sc.name() : "Servicio";
        }
        String base = "Pago Total " + label.trim();
        if (sc == ServiceCode.AEREOS && passengerFullName != null && !passengerFullName.isBlank()) {
            base = base + " - " + passengerFullName.trim();
        }
        if (base.length() > 255) {
            base = base.substring(0, 255);
        }
        return base;
    }

    private String normalizeReservationCode(String rc) {
        if (rc == null) return null;
        String x = rc.trim();
        // Remove spaces to avoid formatting issues (e.g., ' ABC 123 ')
        x = x.replaceAll("\s+", "");
        if (x.isBlank()) return null;
        return x.toUpperCase();
    }

    private String normalizeFlightNumber(String fn) {
        if (fn == null) return null;
        String x = fn.trim();
        x = x.replaceAll("\\s+", "");
        if (x.isBlank()) return null;
        return x.toUpperCase();
    }

    private String buildAirEmissionConcept(Integer passengersCount) {
        int n = 1;
        try {
            if (passengersCount != null && passengersCount > 0) n = passengersCount;
        } catch (Exception ignored) {
        }
        return "Emision Aereos - " + n + " " + (n == 1 ? "Pasajero" : "Pasajeros");
    }

    /**
     * AÉREOS: por cada operación/batch debe existir 1 solo Expense.
     * El frontend envía receiptNumber como batchKey (Date.now()) y passengersCount para construir el concept.
     * Se recalcula el monto del Expense como la suma de todos los ServicePaymentRecord del mismo batch.
     */
    @Transactional
    Long createOrUpdateAirEmissionExpenseAndLink(Long groupId, GroupServiceMenuItem menuItem, ServicePaymentPlan plan, ServicePaymentRecord r, CreateServicePaymentRecordRequest req) {
        // AÉREOS: generar Expense 1:1 por pago (ServicePaymentRecord) para no pisar pagos/emisiones previas.
        // Esto evita que, al registrar el pago de un pasajero, desaparezca en la UI el pago/emisión ya existente de otro pasajero.
        if (groupId == null || menuItem == null || menuItem.getId() == null || plan == null || plan.getId() == null || r == null || r.getId() == null) {
            return null;
        }

        Expense exp = null;
        try {
            exp = expenseRepo.findFirstByServicePaymentRecordId(r.getId()).orElse(null);
        } catch (Exception ignored) {
        }

        boolean created = false;
        if (exp == null) {
            exp = new Expense();
            created = true;
        }

        exp.setGroupId(groupId);
        exp.setMenuItemId(menuItem.getId());
        exp.setServicePaymentRecordId(r.getId());
        exp.setDate(r.getPaymentDate() != null ? r.getPaymentDate() : LocalDate.now());
        exp.setType(ExpenseType.PROVEEDOR);
        exp.setStatus(ExpenseStatus.PAGADO);
        exp.setCurrency(r.getCurrency() != null ? r.getCurrency() : "ARS");
        exp.setPaymentMethod(r.getOneTimeMethod());

        // Un pago = un pasajero
        exp.setConcept(buildAirEmissionConcept(1));
        exp.setCategory("OPERACIONES");
        exp.setAmount(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);

        // Comprobante
        exp.setReceiptNumber(r.getReceiptNumber());
        if (r.getReceiptLast4() != null && !r.getReceiptLast4().isBlank()) {
            exp.setReceiptLast4(r.getReceiptLast4());
        }

        // Mantener blob si existiera (por ejemplo si el comprobante se sube luego)
        if (created) {
            exp.setReceiptBlob(r.getReceiptBlob());
            exp.setReceiptContentType(r.getReceiptContentType());
            exp.setReceiptFileName(r.getReceiptFileName());
        }

        StringBuilder notes = new StringBuilder();
        notes.append("Pago desde Operations. groupId=").append(groupId)
                .append(" menuItemId=").append(menuItem.getId());

        if (r.getReceiptNumber() != null && !r.getReceiptNumber().isBlank()) {
            notes.append(" receiptNumber=").append(r.getReceiptNumber().trim());
        }
        if (req != null && req.passengersCount() != null && req.passengersCount() > 0) {
            notes.append(" airPassengersCount=").append(req.passengersCount());
        }
        if (r.getMemberId() != null) {
            notes.append(" memberId=").append(r.getMemberId());
        }
        if (r.getPassengerFullName() != null && !r.getPassengerFullName().isBlank()) {
            notes.append(" passenger=").append(r.getPassengerFullName().trim());
        }
        if (r.getReservationCode() != null && !r.getReservationCode().isBlank()) {
            notes.append(" reservationCode=").append(r.getReservationCode().trim());
        }

        exp.setNotes(notes.toString());

        try {
            exp = expenseRepo.save(exp);
        } catch (Exception ignored) {
        }
        return (exp != null) ? exp.getId() : null;
    }


    /**
     * AÉREOS: total costo del servicio = suma de importes en ServicePaymentRecord.
     */
    private void syncAirTotalCostFromPayments(Long menuItemId, ServicePaymentPlan plan) {
        if (menuItemId == null || plan == null || plan.getId() == null) return;
        BigDecimal sum = BigDecimal.ZERO;
        try {
            sum = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                    .filter(Objects::nonNull)
                    .map(ServicePaymentRecord::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception ignored) {
        }
        final BigDecimal sumFinal = sum;
        try {
            groupAirRepo.findByMenuItemId(menuItemId).ifPresent(svc -> {
                try {
                    svc.setTotalCost(sumFinal);
                    svc.setTotalCostUpdatedAt(Instant.now());
                    groupAirRepo.save(svc);
                } catch (Exception ignored2) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private BigDecimal sumAccommodationPaid(Long groupId, Long menuItemId) {
        if (groupId == null || menuItemId == null) return BigDecimal.ZERO;

        BigDecimal sum = BigDecimal.ZERO;

        // 1) Sumar registros de pago (fuente principal)
        try {
            ServicePaymentPlan plan = planRepo.findByMenuItemId(menuItemId).orElse(null);
            if (plan != null && plan.getId() != null) {
                sum = recordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId()).stream()
                        .filter(java.util.Objects::nonNull)
                        .map(ServicePaymentRecord::getAmount)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
        } catch (Exception ignored) {
        }

        // 2) Sumar pagos legacy/orfandad que existan solo como Expense (sin servicePaymentRecordId)
        try {
            for (Expense e : expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId)) {
                if (e == null) continue;
                if (e.getServicePaymentRecordId() != null) continue;
                BigDecimal a = e.getAmount();
                if (a != null) sum = sum.add(a);
            }
        } catch (Exception ignored) {
        }

        return sum;
    }


    /**
     * ALOJAMIENTOS: total costo del servicio = suma de pagos registrados (Expenses) para el menu item.
     */
    private void syncAccommodationTotalCostFromPayments(Long groupId, Long menuItemId) {
        if (groupId == null || menuItemId == null) return;
        BigDecimal sum = BigDecimal.ZERO;
        try {
            BigDecimal x = sumAccommodationPaid(groupId, menuItemId);
            if (x != null) sum = x;
        } catch (Exception ignored) {
        }
        final BigDecimal sumFinal = sum;
        try {
            accommodationRepo.findByMenuItemId(menuItemId).ifPresent(svc -> {
                try {
                    svc.setTotalCost(sumFinal);
                    svc.setTotalCostUpdatedAt(Instant.now());
                    accommodationRepo.save(svc);
                } catch (Exception ignored2) {
                }
            });
        } catch (Exception ignored) {
        }
    }


    private String normalizeCardNumber(String cn) {
        if (cn == null) return null;
        String x = cn.trim();
        // Remove spaces and hyphens
        x = x.replaceAll("[\s-]+", "");
        if (x.isBlank()) return null;
        return x;
    }

    private PaymentOneTimeMethod parseOneTimeMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String norm = raw.trim().toUpperCase();
        try {
            norm = java.text.Normalizer.normalize(norm, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
        } catch (Exception ignored) {
        }
        // Convert any separator to underscore ("Tarjeta Débito" -> "TARJETA_DEBITO")
        norm = norm.replaceAll("[^A-Z0-9]+", "_");
        norm = norm.replaceAll("^_+|_+$", "");

        // aliases
        if (norm.equals("CASH")) norm = "EFECTIVO";
        if (norm.equals("TRANSFERENCIA_BANCARIA")) norm = "TRANSFERENCIA";
        if (norm.equals("DEBITO")) norm = "TARJETA_DEBITO";
        if (norm.equals("CREDITO")) norm = "TARJETA_CREDITO";
        if (norm.equals("TARJETA")) norm = "TARJETA_CREDITO";
        if (norm.equals("CARD")) norm = "TARJETA_CREDITO";

        return PaymentOneTimeMethod.valueOf(norm);
    }

}
