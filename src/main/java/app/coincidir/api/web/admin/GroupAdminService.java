package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.TravelRequestAdminDto;
import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.domain.RequestStatus;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.UserAccountRepository;
import app.coincidir.api.repository.MemberPaymentPlanRepository;
import app.coincidir.api.repository.ExpenseRepository;
import app.coincidir.api.domain.conciliation.ConciliationStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.domain.payment.PaymentPlanType;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.ServiceDefinition;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.TravelRequestAirService;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.domain.operations.GroupOperations;
import app.coincidir.api.repository.FinancialMovementConciliationRepository;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.repository.ServiceDefinitionRepository;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.repository.TravelRequestAirServiceRepository;
import app.coincidir.api.repository.GroupOperationsRepository;
import app.coincidir.api.service.MemberPaymentsBootstrapService;
import app.coincidir.api.service.OperationsRoleGuardService;
import app.coincidir.api.web.admin.dto.GroupMemberDto;
import app.coincidir.api.web.admin.dto.GroupSummaryDto;
import app.coincidir.api.web.admin.dto.AddManualMemberRequest;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import app.coincidir.api.web.admin.dto.CandidateSummaryDto;
import app.coincidir.api.web.admin.dto.AddMembersRequest;

import app.coincidir.api.web.admin.dto.UpdateGroupDatesRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.coincidir.api.repository.MemberFerryServiceRepository;
import app.coincidir.api.repository.MemberTransferServiceRepository;
import app.coincidir.api.repository.MemberDestinationTransferServiceRepository;
import app.coincidir.api.repository.MemberAccommodationServiceRepository;
import app.coincidir.api.repository.MemberAirServiceRepository;
import app.coincidir.api.repository.MemberOptionalServiceMenuItemRepository;
import app.coincidir.api.repository.MemberOptionalExcursionServiceRepository;
import app.coincidir.api.repository.MemberOptionalTravelAssistanceServiceRepository;
import app.coincidir.api.repository.MemberOptionalLuggageServiceRepository;
import app.coincidir.api.web.admin.OperationConfirmedEmailService;
import org.springframework.jdbc.core.JdbcTemplate;
@Service
@RequiredArgsConstructor
public class GroupAdminService {

    private static final String TEMP_GROUP_DEST = "__TEMP_PAYMENTS__";
    private static final String TEMP_GROUP_WHEN = "TEMP";

    private final TravelGroupRepository groupRepo;
    private final TravelRequestRepository requestRepo;
    private final UserAccountRepository userRepo;
    private final PasswordEncoder encoder;
    private final MemberPaymentPlanRepository paymentPlanRepo;
    private final MemberPaymentRecordRepository paymentRecordRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;
    private final MemberPaymentsBootstrapService paymentsBootstrapService;
    private final OperationsRoleGuardService operationsRoleGuardService;

    private final GroupServiceMenuItemRepository groupServiceMenuItemRepo;
    private final ServiceDefinitionRepository serviceDefinitionRepo;
    private final GroupAirServiceRepository groupAirServiceRepo;
    private final TravelRequestAirServiceRepository travelRequestAirServiceRepo;
    private final GroupOperationsRepository groupOperationsRepo;




    private final MemberFerryServiceRepository memberFerryRepo;
    private final MemberTransferServiceRepository memberTransferRepo;
    private final MemberDestinationTransferServiceRepository memberDestinationTransferRepo;
    private final MemberAccommodationServiceRepository memberAccommodationRepo;
    private final MemberAirServiceRepository memberAirRepo;
    private final MemberOptionalServiceMenuItemRepository memberOptionalMenuRepo;
    private final MemberOptionalExcursionServiceRepository memberOptionalExcursionRepo;
    private final MemberOptionalTravelAssistanceServiceRepository memberOptionalTravelAssistanceRepo;
    private final MemberOptionalLuggageServiceRepository memberOptionalLuggageRepo;
    private final ExpenseRepository expenseRepo;
    private final OperationConfirmedEmailService operationConfirmedEmailService;
    private final JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;

    public List<GroupSummaryDto> listGroups(GroupStatus status, Boolean paymentsUnverified, String q) {
        String qNorm = (q == null) ? null : q.trim().toLowerCase();
        boolean opsRestricted = operationsRoleGuardService != null && operationsRoleGuardService.restrictToPaidConfirmed();
        // Para OPERATIONS: no filtramos por status (el panel filtra client-side por CLOSED).
        // Importante: NO filtrar por pagos/operación confirmada, solo informar estados para habilitar/deshabilitar acciones.
        boolean operationalFilter = status != null && isOperationalFlowStatus(status);
        GroupStatus effectiveStatus = (opsRestricted || operationalFilter) ? null : status;

        List<GroupSummaryDto> out = groupRepo.findAllWithMembers(effectiveStatus).stream()
                .filter(g -> !isTempGroup(g))
                .map(g -> {
                    ensureSummary(g);          // autocompleta si falta
                    return toDto(g);
                })
                .toList();

        if (opsRestricted) {
            // Para OPERATIONS: solo devolver grupos habilitados (sin pagos a conciliar y con operación confirmada)
            return out.stream()
                    .filter(dto -> Boolean.TRUE.equals(dto.getPaymentsVerified()) && Boolean.TRUE.equals(dto.getOperationConfirmed()))
                    .toList();
        }

        if (operationalFilter) {
            out = out.stream()
                    .filter(dto -> dto != null && dto.getStatus() != null && dto.getStatus().equalsIgnoreCase(status.name()))
                    .toList();
        }

        if (Boolean.TRUE.equals(paymentsUnverified)) {
            return out.stream()
                    .filter(dto -> dto.getMembers() == null || dto.getMembers().isEmpty()
                            || dto.getMembers().stream().anyMatch(m -> m.paymentOk() == null || !m.paymentOk()))
                    .toList();
        }

        if (qNorm != null && !qNorm.isBlank()) {
            final String qq = qNorm;
            out = out.stream()
                    .filter(dto -> {
                        if (dto == null) return false;
                        if (containsIgnoreCase(dto.getDestination(), qq)) return true;
                        if (containsIgnoreCase(dto.getTravelDate(), qq)) return true;
                        if (containsIgnoreCase(dto.getStatus(), qq)) return true;
                        if (containsIgnoreCase(dto.getDepartureMonth(), qq)) return true;
                        if (containsIgnoreCase(dto.getTravelStartDate(), qq)) return true;
                        if (containsIgnoreCase(dto.getTravelEndDate(), qq)) return true;
                        if (dto.getMembers() != null) {
                            for (var m : dto.getMembers()) {
                                if (m == null) continue;
                                if (containsIgnoreCase(m.fullName(), qq)) return true;
                                if (containsIgnoreCase(m.email(), qq)) return true;
                            }
                        }
                        return false;
                    })
                    .toList();
        }

        return out;
    }

    private boolean containsIgnoreCase(String value, String q) {
        if (value == null || q == null) return false;
        return value.toLowerCase().contains(q);
    }

    private static boolean isOperationalFlowStatus(GroupStatus s) {
        return s == GroupStatus.EN_CONCILIACION
                || s == GroupStatus.CONCILIADO
                || s == GroupStatus.EN_OPERACIONES_SC
                || s == GroupStatus.EN_OPERACIONES;
    }

    private boolean isTempGroup(TravelGroup g) {
        if (g == null) return false;
        String d = null;
        try {
            d = g.getDestination();
        } catch (Exception ignored) {}
        return d != null && d.trim().equalsIgnoreCase(TEMP_GROUP_DEST);
    }


    @Transactional
    public int disband(Long groupId) {
        // setea todos los requests del grupo a NEW + group=null
        int affected = requestRepo.resetAllByGroup(groupId);
        // opcional: limpiar resumen del grupo
        groupRepo.findById(groupId).ifPresent(g -> {
            g.setTravelDateLabel(null);
            g.setCommonPrefs(null);
            groupRepo.save(g);
        });
        return affected;
    }

    @Transactional
    public int removeMember(Long groupId, Long requestId) {
        // If the member has payments associated to this group, clear them when removing
        // so they don't appear when the member is later added to another group.
        TravelRequest before = requestId == null ? null : requestRepo.findById(requestId).orElse(null);

        boolean memberInGroup = false;
        if (before != null && before.getGroup() != null && java.util.Objects.equals(before.getGroup().getId(), groupId)) {
            memberInGroup = true;
        }

        // Validación: un grupo no puede quedar con menos de 2 miembros
        if (memberInGroup) {
            long currentCount = requestRepo.countByGroupId(groupId);
            if (currentCount < 3) {
                throw new BadRequestException("El grupo no puede tener menos de 2 miembros.");
            }
        }


        boolean hadPayments = false;
        if (before != null && before.getGroup() != null && Objects.equals(before.getGroup().getId(), groupId)) {
            boolean hadDeposit = before.getDepositAmount() != null && before.getDepositAmount().compareTo(BigDecimal.ZERO) > 0;
            boolean hadPlan = paymentPlanRepo.findByGroupIdAndMemberId(groupId, requestId).isPresent();
            boolean hadRecords = false;
            try {
                var existing = paymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, requestId);
                hadRecords = existing != null && !existing.isEmpty();
            } catch (Exception ignore) {
            }
            hadPayments = hadDeposit || hadPlan || hadRecords;
        }

        int affected = requestRepo.resetOneFromGroup(groupId, requestId);

        if (affected > 0 && hadPayments) {
            // 1) Delete payments/plan (and conciliations) tied to the previous group
            cleanupMemberPaymentsForGroup(groupId, requestId);

            // 2) Clear deposit fields stored in the request itself
            em.clear();
            TravelRequest after = requestRepo.findById(requestId).orElse(null);
            if (after != null) {
                after.setDepositAmount(null);
                after.setDepositPaymentMethod(null);
                after.setDepositDate(null);
                after.setDepositNotes(null);
                requestRepo.save(after);
            }
        }
        // refrescar resumen del grupo con los miembros restantes
        groupRepo.findById(groupId).ifPresent(g -> {
            refreshSummary(g);
            groupRepo.save(g);
        });
        return affected;
    }

    private void cleanupMemberPaymentsForGroup(Long groupId, Long memberId) {
        if (groupId == null || memberId == null) return;

        // Delete payment records for this member in this group
        var records = paymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId);
        java.util.Set<Long> recordIds = records == null ? java.util.Collections.emptySet()
                : records.stream()
                .map(MemberPaymentRecord::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        if (records != null && !records.isEmpty()) {
            paymentRecordRepo.deleteAll(records);
        }

        if (!recordIds.isEmpty()) {
            var conc = conciliationRepo.findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, recordIds);
            if (conc != null && !conc.isEmpty()) {
                conciliationRepo.deleteAll(conc);
            }
        }

        // Delete the payment plan (installments are orphanRemoval)
        paymentPlanRepo.findByGroupIdAndMemberId(groupId, memberId).ifPresent(paymentPlanRepo::delete);
    }

    @Transactional
    public GroupSummaryDto updateStatus(Long groupId, GroupStatus status) {
        TravelGroup g = groupRepo.findById(groupId).orElseThrow();
        g.setStatus(status);
        if (status == GroupStatus.FORMED) {
            refreshSummary(g);                 // calcula travelDateLabel + commonPrefs
        }
        groupRepo.save(g);
        return toDto(g);
    }


    @Transactional
    public GroupSummaryDto assignToMe(Long groupId, String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        UserAccount me = userRepo.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        String role = me.getRole() == null ? null : me.getRole().trim();
        if (role == null || (!role.equalsIgnoreCase("SELLER") && !role.equalsIgnoreCase("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        TravelGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado"));

        Long currentSellerId = g.getSellerUserId();
        if (currentSellerId != null && !currentSellerId.equals(me.getId())) {
            throw new BadRequestException("El grupo ya está asignado a otro vendedor");
        }

        if (currentSellerId == null) {
            g.setSellerUserId(me.getId());
            groupRepo.save(g);
        }

        return toDto(g);
    }

    private Long tryResolveSellerId(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            UserAccount me = userRepo.findByEmail(email).orElse(null);
            if (me == null) return null;
            if (me.getRole() == null) return null;
            String role = me.getRole().trim();
            if (!role.equalsIgnoreCase("SELLER") && !role.equalsIgnoreCase("ADMIN")) return null;
            return me.getId();
        } catch (Exception ignored) {
            return null;
        }
    }

    /* -------------------- DTO mapping -------------------- */

    // ===== Detalle de solicitud (pasajero) para panel admin =====

    @Transactional
    public TravelRequestAdminDto getRequestDetail(Long requestId) {
        TravelRequest r = requestRepo.findById(requestId).orElseThrow();
        return toAdminDto(r);
    }

    @Transactional
    public TravelRequestAdminDto updateRequestDetail(Long requestId, TravelRequestAdminDto dto) {
        TravelRequest r = requestRepo.findById(requestId).orElseThrow();

        // Datos “de viaje”
        r.setDestination(dto.destination());
        r.setDatePresetId(dto.datePresetId());
        r.setWhenLabel(dto.whenLabel());
        r.setSharedRoom(dto.sharedRoom());
        r.setLuggageCount(dto.luggageCount());
        r.setIncludesTours(dto.includesTours());
        r.setTravelAssistance(dto.travelAssistance());
        r.setCompanionPreference(dto.companionPreference());
        r.setAgeMin(dto.ageMin());
        r.setAgeMax(dto.ageMax());
        r.setPaxMin(dto.paxMin());
        r.setPaxMax(dto.paxMax());
        r.setTravelersTotal(dto.travelersTotal());
        r.setTravelersAdults(dto.travelersAdults());
        r.setTravelersMinors(dto.travelersMinors());
        r.setSmokeFree(dto.smokeFree());
        r.setGender(dto.gender());

        // Datos de contacto / personales
        r.setName(dto.name());
        r.setEmail(dto.email());
        r.setPhone(dto.phone());
        r.setPhoneCountryCode(dto.phoneCountryCode() == null ? null : dto.phoneCountryCode().trim());

        // DNI / País / Tipo de documento
        r.setDni(dto.dni());
        if (dto.documentType() != null && !dto.documentType().isBlank()) {
            r.setDocumentType(dto.documentType().trim().toUpperCase());
        }

        if (dto.countryId() != null) {
            r.setCountryId(dto.countryId());
            String resolved = resolveCountryName(dto.countryId());
            r.setCountry(resolved != null ? resolved : dto.country());
        } else {
            r.setCountryId(null);
            r.setCountry(dto.country());
        }

        r.setCity(dto.city());
        r.setProvince(dto.province());
        r.setLocality(dto.locality());
        r.setPostalCode(dto.postalCode());
        r.setBirthDate(dto.birthDate());
        boolean noExpiry = Boolean.TRUE.equals(dto.documentNoExpiry());
        boolean notApplicable = Boolean.TRUE.equals(dto.documentNotApplicable());
        r.setDocumentNoExpiry(noExpiry);
        r.setDocumentNotApplicable(notApplicable);
        r.setDocumentExpiryDate(noExpiry || notApplicable ? null : dto.documentExpiryDate());
        // Seña
        r.setDepositAmount(dto.depositAmount());
        r.setDepositPaymentMethod(dto.depositPaymentMethod());
        r.setDepositDate(dto.depositDate());
        r.setDepositNotes(dto.depositNotes());
        r.setTz(dto.tz());

        requestRepo.save(r);
        return toAdminDto(r);
    }



    @Transactional
    public java.util.List<app.coincidir.api.web.admin.dto.UnassignedPassengerDto> listUnassignedRequests() {
        return listUnassignedRequests(null);
    }

    @Transactional
    public java.util.List<app.coincidir.api.web.admin.dto.UnassignedPassengerDto> listUnassignedRequests(String mode) {
        List<TravelRequest> reqs = requestRepo.findAllEligible();

        // Filtro server-side opcional (para evitar traer todo si el front filtra por tipo)
        if (mode != null && !mode.isBlank()) {
            String m = mode.trim().toUpperCase();
            if ("INDIVIDUAL".equals(m)) {
                reqs = reqs.stream().filter(r -> r != null && r.getTravelStartDate() != null).toList();
            } else if ("GROUP".equals(m) || "GRUPAL".equals(m)) {
                reqs = reqs.stream().filter(r -> r != null && r.getTravelStartDate() == null).toList();
            }
        }

        // Para agrupación por fecha:
        // - GROUP: tomamos departureDate del AirService asociado a la TravelRequest (si existe)
        // - INDIVIDUAL: usamos travel_start_date
        Map<Long, LocalDate> travelDateByRequestId = Map.of();
        try {
            List<Long> ids = reqs.stream().map(TravelRequest::getId).filter(Objects::nonNull).toList();
            if (!ids.isEmpty()) {
                travelDateByRequestId = travelRequestAirServiceRepo.findAllByRequest_IdIn(ids)
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(a -> a.getRequest() != null && a.getRequest().getId() != null)
                        .filter(a -> a.getDepartureDate() != null)
                        .collect(Collectors.toMap(
                                a -> a.getRequest().getId(),
                                TravelRequestAirService::getDepartureDate,
                                (a, b) -> a
                        ));
            }
        } catch (Exception ignored) {
            // si algo falla, devolvemos sin fecha
        }

        final Map<Long, LocalDate> finalMap = travelDateByRequestId;
        return reqs.stream()
                .map(r -> {
                    boolean isIndividual = r != null && r.getTravelStartDate() != null;
                    LocalDate travelDate = finalMap.get(r.getId());
                    if (travelDate == null && isIndividual) {
                        travelDate = r.getTravelStartDate();
                    }

                    return app.coincidir.api.web.admin.dto.UnassignedPassengerDto.builder()
                            .id(r.getId())
                            .destination(r.getDestination())
                            .whenLabel(r.getWhenLabel())
                            .datePresetId(r.getDatePresetId())
                            .travelDate(travelDate)
                            .travelStartDate(r.getTravelStartDate())
                            .individual(isIndividual)
                            .ageMin(r.getAgeMin())
                            .ageMax(r.getAgeMax())
                            .name(r.getName())
                            .birthDate(r.getBirthDate())
                            .companionPreference(r.getCompanionPreference())
                            .gender(r.getGender())
                            .build();
                })
                .toList();
    }
    private TravelRequestAdminDto toAdminDto(TravelRequest r) {
        Boolean depositPaid = r.getDepositAmount() != null && r.getDepositAmount().compareTo(BigDecimal.ZERO) > 0;

        String metaFirst = null;
        String metaLast = null;
        String metaDocType = null;
        String metaDocNum = null;
        LocalDate metaTravelStartNew = null;
        try {
            String notes = r.getDepositNotes();
            if (notes != null) {
                String t = notes.trim();
                if (t.startsWith("{") && t.endsWith("}")) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(t);
                    if (root != null && root.isObject()) {
                        metaFirst = text(root, "firstName");
                        metaLast = text(root, "lastName");
                        metaDocType = text(root, "documentType");
                        metaDocNum = text(root, "documentNumber");

                        // Respaldo: en carga GRUPAL guardamos "Fecha de viaje desde (nueva)" aquí.
                        String ts = text(root, "travelStartDateNew");
                        if (ts != null && !ts.isBlank()) {
                            try {
                                metaTravelStartNew = LocalDate.parse(ts.trim());
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback: si no vienen separados, intentar derivar desde name
        String derivedFirst = metaFirst;
        String derivedLast = metaLast;
        if ((derivedFirst == null || derivedFirst.isBlank()) && (derivedLast == null || derivedLast.isBlank())) {
            try {
                String nm = r.getName();
                if (nm != null) {
                    String[] parts = nm.trim().split("\\s+", 2);
                    if (parts.length >= 1) derivedFirst = parts[0];
                    if (parts.length >= 2) derivedLast = parts[1];
                }
            } catch (Exception ignored) {
            }
        }

        // Preferir columna document_type sobre el JSON de depositNotes
        if ((metaDocType == null || metaDocType.isBlank()) && r.getDocumentType() != null && !r.getDocumentType().isBlank()) {
            metaDocType = r.getDocumentType();
        }
        String docNumber = (metaDocNum != null && !metaDocNum.isBlank()) ? metaDocNum : r.getDni();

        // Para GRUPALES: el campo "Fecha de viaje desde (nueva)" debe traer el mismo valor que
        // "Fecha del viaje (desde)". En el backend, esa fecha suele venir del aéreo (departureDate).
        LocalDate effectiveTravelStartDate = r.getTravelStartDate();
        if (effectiveTravelStartDate == null) {
            if (metaTravelStartNew != null) {
                effectiveTravelStartDate = metaTravelStartNew;
            } else {
                try {
                    if (r.getId() != null) {
                        effectiveTravelStartDate = travelRequestAirServiceRepo.findByRequest_Id(r.getId())
                                .map(TravelRequestAirService::getDepartureDate)
                                .orElse(null);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return new TravelRequestAdminDto(
                r.getId(),
                r.getDestination(),
                r.getDatePresetId(),
                r.getWhenLabel(),
                effectiveTravelStartDate,
                r.getTravelEndDate(),
                r.getSharedRoom(),
                r.getLuggageCount(),
                r.getIncludesTours(),
                r.getTravelAssistance(),
                r.getCompanionPreference(),
                r.getAgeMin(),
                r.getAgeMax(),
                r.getPaxMin(),
                r.getPaxMax(),
                r.getTravelersTotal(),
                r.getTravelersAdults(),
                r.getTravelersMinors(),
                r.getSmokeFree(),
                r.getGender(),
                derivedFirst,
                derivedLast,
                r.getName(),
                r.getEmail(),
                r.getPhone(),
                r.getPhoneCountryCode(),
                r.getDni(),
                metaDocType,
                docNumber,
                r.getCountryId(),
                r.getCountry(),
                r.getCity(),
                r.getProvince(),
                r.getLocality(),
                r.getPostalCode(),
                r.getBirthDate(),
                r.getDocumentExpiryDate(),
                r.getDocumentNoExpiry(),
                r.getDocumentNotApplicable(),
                r.getDepositAmount(),
                r.getDepositPaymentMethod(),
                r.getDepositDate(),
                r.getDepositNotes(),
                depositPaid,
                r.getTz()
        );
    }

    private String resolveCountryName(Long countryId) {
        if (countryId == null) return null;
        try {
            Object v = em.createNativeQuery("SELECT descripcion FROM paises WHERE id = ?")
                    .setParameter(1, countryId)
                    .getSingleResult();
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal computeQuotedTotal(Long groupId) {
        if (groupId == null) return BigDecimal.ZERO;
        // Prioridad: suma de cotizaciones de la pantalla Cotización (group_service_menu_item.quoted_value)
        BigDecimal menuTotal = nz(groupServiceMenuItemRepo.sumQuotedValueByGroupId(groupId));
        if (menuTotal.compareTo(BigDecimal.ZERO) > 0) return menuTotal;
        // Fallback: suma de servicios por miembro (member_*_service.quoted_value)
        return nz(memberFerryRepo.sumQuotedValueByGroupId(groupId))
                .add(nz(memberTransferRepo.sumQuotedValueByGroupId(groupId)))
                .add(nz(memberDestinationTransferRepo.sumQuotedValueByGroupId(groupId)))
                .add(nz(memberAccommodationRepo.sumQuotedValueByGroupId(groupId)))
                .add(nz(memberAirRepo.sumQuotedValueByGroupId(groupId)));
    }

    private BigDecimal computeMemberQuotedTotal(Long memberId) {
        if (memberId == null) return BigDecimal.ZERO;
        // Intentar obtener el total del grupo desde la pantalla Cotización y dividir por miembros
        try {
            var req = requestRepo.findById(memberId).orElse(null);
            if (req != null && req.getGroup() != null && req.getGroup().getId() != null) {
                Long groupId = req.getGroup().getId();
                BigDecimal menuTotal = nz(groupServiceMenuItemRepo.sumQuotedValueByGroupId(groupId));
                if (menuTotal.compareTo(BigDecimal.ZERO) > 0) {
                    long memberCount = requestRepo.countByGroupId(groupId);
                    if (memberCount > 0) {
                        return menuTotal.divide(java.math.BigDecimal.valueOf(memberCount), 2, java.math.RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception ignored) {}
        // Fallback: suma de servicios por miembro
        return nz(memberFerryRepo.sumQuotedValueByMemberId(memberId))
                .add(nz(memberTransferRepo.sumQuotedValueByMemberId(memberId)))
                .add(nz(memberDestinationTransferRepo.sumQuotedValueByMemberId(memberId)))
                .add(nz(memberAccommodationRepo.sumQuotedValueByMemberId(memberId)))
                .add(nz(memberAirRepo.sumQuotedValueByMemberId(memberId)));
    }

    private BigDecimal computeGroupTotalToCharge(TravelGroup g, List<MemberPaymentPlan> plans) {
        if (plans == null || plans.isEmpty()) return BigDecimal.ZERO;

        // INDIVIDUALES: si hay Titular Pago configurado, el total a cobrar del grupo es el del titular.
        if (g != null && g.getTravelStartDate() != null) {
            Long titularId = readPaymentTitularMemberId(g);
            if (titularId != null) {
                for (MemberPaymentPlan p : plans) {
                    if (p != null && p.getMemberId() != null && p.getMemberId().equals(titularId)) {
                        return nz(p.getTotalAmount());
                    }
                }
                // Si no se encuentra el plan del titular, caer al comportamiento estándar (suma).
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (MemberPaymentPlan p : plans) {
            if (p == null) continue;
            sum = sum.add(nz(p.getTotalAmount()));
        }
        return sum;
    }



    private GroupSummaryDto toDto(TravelGroup g) {
        var reqMembers = g.getMembers() == null ? List.<TravelRequest>of() : g.getMembers();

        // Total a cobrar (Pagos) para exponer en el resumen del grupo.
        // Se usa luego en Operations para calcular la venta promedio por pasajero.
        BigDecimal groupTotalToCharge = BigDecimal.ZERO;

        // compute paymentOk for this group (payments module) based on conciliation verification
        final java.util.Map<Long, Boolean> paymentOkByMemberId;
        boolean groupPaymentsConciliated = false;
        // Hoisted al scope exterior para usarse en el cálculo de effectiveStatus (EN_COTIZACION)
        java.util.List<MemberPaymentRecord> allPaymentRecords = java.util.Collections.emptyList();

        if (g.getId() != null && reqMembers != null && !reqMembers.isEmpty()) {
            final Long groupId = g.getId();
            var plans = paymentPlanRepo.findAllByGroupIdWithInstallments(groupId);
            var records = paymentRecordRepo.findAllByGroupIdOrderByPaymentDateDescIdDesc(groupId);
            allPaymentRecords = records != null ? records : java.util.Collections.emptyList();

            if (plans != null && !plans.isEmpty()) {
                // Calcular total a cobrar.
                groupTotalToCharge = computeGroupTotalToCharge(g, plans);

                java.util.Set<Long> recordIds = records.stream()
                        .map(MemberPaymentRecord::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());

                java.util.Map<Long, FinancialMovementConciliation> conciliationByRecordId;
                if (recordIds.isEmpty()) {
                    conciliationByRecordId = java.util.Collections.emptyMap();
                } else {
                    conciliationByRecordId = conciliationRepo
                            .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, recordIds)
                            .stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    FinancialMovementConciliation::getMovementId,
                                    c -> c,
                                    (a, b) -> a
                            ));
                }

                // Conciliación a nivel grupo: todos los pagos registrados deben estar VERIFIED.
                groupPaymentsConciliated = areAllPaymentRecordsConciliated(records, conciliationByRecordId);

                java.util.Map<Long, java.util.List<MemberPaymentRecord>> recordsByMemberId = records.stream()
                        .filter(r -> r.getMemberId() != null)
                        .collect(java.util.stream.Collectors.groupingBy(MemberPaymentRecord::getMemberId));

                paymentOkByMemberId = plans.stream().collect(java.util.stream.Collectors.toMap(
                        p -> p.getMemberId(),
                        p -> isMemberPaymentsVerified(p, recordsByMemberId.get(p.getMemberId()), conciliationByRecordId),
                        (a, b) -> a
                ));
            } else {
                // Fallback: pagos registrados como Expenses (pago temporal desde Admin Panel) + conciliación por expense
                // En este modo histórico no existe el concepto de "total a cobrar" por plan.
                // Como aproximación, devolvemos el total cotizado del grupo.
                groupTotalToCharge = computeQuotedTotal(groupId);

                java.util.Map<Long, java.util.List<app.coincidir.api.domain.expense.Expense>> expensesByMemberId = new java.util.HashMap<>();
                java.util.Set<Long> expenseIds = new java.util.HashSet<>();

                for (TravelRequest mbr : reqMembers) {
                    if (mbr == null || mbr.getId() == null) continue;
                    java.util.List<app.coincidir.api.domain.expense.Expense> exps;
                    try {
                        exps = expenseRepo.findAllByGroupIdAndNotesContaining(groupId, "pasajeroId=" + mbr.getId());
                    } catch (Exception ex) {
                        exps = java.util.List.of();
                    }
                    expensesByMemberId.put(mbr.getId(), exps);
                    if (exps != null) {
                        for (app.coincidir.api.domain.expense.Expense e : exps) {
                            if (e != null && e.getId() != null) expenseIds.add(e.getId());
                        }
                    }
                }

                java.util.Map<Long, FinancialMovementConciliation> conciliationByExpenseId;
                if (expenseIds.isEmpty()) {
                    conciliationByExpenseId = java.util.Collections.emptyMap();
                } else {
                    conciliationByExpenseId = conciliationRepo
                            .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.EXPENSE_RECORD, expenseIds)
                            .stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    FinancialMovementConciliation::getMovementId,
                                    c -> c,
                                    (a, b) -> a
                            ));
                }

                // Conciliación a nivel grupo (fallback): todos los gastos temporales deben estar VERIFIED.
                groupPaymentsConciliated = areAllExpensesConciliated(expenseIds, conciliationByExpenseId);

                java.util.Map<Long, Boolean> tmp = new java.util.HashMap<>();
                for (TravelRequest mbr : reqMembers) {
                    if (mbr == null || mbr.getId() == null) continue;
                    tmp.put(mbr.getId(), isMemberExpensesVerified(expensesByMemberId.get(mbr.getId()), conciliationByExpenseId));
                }
                paymentOkByMemberId = tmp;
            }
        } else {
            paymentOkByMemberId = java.util.Collections.emptyMap();
        }

        // Si no se pudo calcular por planes (p.ej. grupo sin miembros o sin módulo pagos),
        // devolver al menos el total cotizado para que el frontend tenga un valor consistente.
        if (groupTotalToCharge == null || groupTotalToCharge.compareTo(BigDecimal.ZERO) < 0) {
            groupTotalToCharge = BigDecimal.ZERO;
        }
        if (groupTotalToCharge.compareTo(BigDecimal.ZERO) == 0 && g.getId() != null) {
            try {
                groupTotalToCharge = computeQuotedTotal(g.getId());
            } catch (Exception ignored) {
            }
        }


        // Calcular totalVenta (operación completa) y perMemberVenta (para columna Venta en detalle)
        // SOLO cuando hay pagos registrados reales. Sin pagos → null → frontend muestra "-".
        BigDecimal totalVenta = null;
        BigDecimal perMemberVenta = null;
        if (g.getId() != null && reqMembers != null && !reqMembers.isEmpty()
                && !allPaymentRecords.isEmpty()) {
            try {
                var allPlansForVenta = paymentPlanRepo.findAllByGroupIdWithInstallments(g.getId());
                if (allPlansForVenta != null && !allPlansForVenta.isEmpty()) {
                    // Prioridad: usar el plan del Titular de pago si está configurado
                    Long titularIdForVenta = readPaymentTitularMemberId(g);
                    BigDecimal operationTotal = null;
                    if (titularIdForVenta != null) {
                        for (MemberPaymentPlan p : allPlansForVenta) {
                            if (titularIdForVenta.equals(p.getMemberId())
                                    && p.getTotalAmount() != null
                                    && p.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                                operationTotal = p.getTotalAmount();
                                break;
                            }
                        }
                    }
                    // Fallback: usar el mayor totalAmount entre todos los planes
                    if (operationTotal == null || operationTotal.compareTo(BigDecimal.ZERO) == 0) {
                        for (MemberPaymentPlan p : allPlansForVenta) {
                            if (p.getTotalAmount() != null && p.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                                if (operationTotal == null || p.getTotalAmount().compareTo(operationTotal) > 0) {
                                    operationTotal = p.getTotalAmount();
                                }
                            }
                        }
                    }
                    if (operationTotal != null && operationTotal.compareTo(BigDecimal.ZERO) > 0) {
                        totalVenta = operationTotal;
                        int memberCount = reqMembers.size();
                        perMemberVenta = operationTotal.divide(
                                java.math.BigDecimal.valueOf(memberCount), 2, java.math.RoundingMode.HALF_UP);
                    }
                }
            } catch (Exception ignored) {}
        }

        final BigDecimal perMemberVentaFinal = perMemberVenta;
        var members = reqMembers == null ? List.<GroupMemberDto>of()
                : reqMembers.stream().map(r -> toMember(r, paymentOkByMemberId.get(r.getId()), perMemberVentaFinal)).toList();

        // Payments verified (Operations Panel):
        // - Para INDIVIDUALES, se valida únicamente el Titular Pago (quien paga por la operación completa).
        // - Para GRUPALES, se mantiene la validación de TODOS los miembros.
        Boolean groupPaymentsVerified;
        if (g.getTravelStartDate() != null) {
            Long titularId = readPaymentTitularMemberId(g);
            if (titularId != null) {
                groupPaymentsVerified = Boolean.TRUE.equals(paymentOkByMemberId.get(titularId));
            } else {
                // Fallback conservador: si no hay titular configurado, mantener la lógica previa.
                groupPaymentsVerified = (members != null && !members.isEmpty())
                        ? members.stream().allMatch(m -> m != null && Boolean.TRUE.equals(m.paymentOk()))
                        : Boolean.FALSE;
            }
        } else {
            groupPaymentsVerified = (members != null && !members.isEmpty())
                    ? members.stream().allMatch(m -> m != null && Boolean.TRUE.equals(m.paymentOk()))
                    : Boolean.FALSE;
        }

        // Opcionales (adicionales) por miembro: se usan en Operations para mostrar el botón Adicionales.
        // Además devolvemos un resumen (totales USD + completitud) para que el panel pinte de entrada
        // sin necesidad de abrir/cerrar el modal.
        Boolean hasOptionals = Boolean.FALSE;
        BigDecimal optExcUsd = BigDecimal.ZERO;
        BigDecimal optAssistUsd = BigDecimal.ZERO;
        BigDecimal optEquipUsd = BigDecimal.ZERO;
        BigDecimal optTotalUsd = BigDecimal.ZERO;
        Boolean optAllCompleted = Boolean.FALSE;

        if (g.getId() != null) {
            try {
                long totalMenuItems = memberOptionalMenuRepo != null ? memberOptionalMenuRepo.countByGroupId(g.getId()) : 0L;
                hasOptionals = totalMenuItems > 0;

                if (hasOptionals) {
                    // Totales en USD
                    optExcUsd = memberOptionalExcursionRepo != null ? safeBig(memberOptionalExcursionRepo.sumSaleByGroupId(g.getId())) : BigDecimal.ZERO;
                    optAssistUsd = memberOptionalTravelAssistanceRepo != null ? safeBig(memberOptionalTravelAssistanceRepo.sumSaleByGroupId(g.getId())) : BigDecimal.ZERO;
                    optEquipUsd = memberOptionalLuggageRepo != null ? safeBig(memberOptionalLuggageRepo.sumSaleByGroupId(g.getId())) : BigDecimal.ZERO;
                    optTotalUsd = optExcUsd.add(optAssistUsd).add(optEquipUsd);

                    // Completitud: todas las filas de menu_item deben tener su registro asociado.
                    long done = 0L;
                    if (memberOptionalExcursionRepo != null) done += memberOptionalExcursionRepo.countByGroupId(g.getId());
                    if (memberOptionalTravelAssistanceRepo != null) done += memberOptionalTravelAssistanceRepo.countByGroupId(g.getId());
                    if (memberOptionalLuggageRepo != null) done += memberOptionalLuggageRepo.countByGroupId(g.getId());
                    optAllCompleted = totalMenuItems > 0 && done == totalMenuItems;
                }
            } catch (Exception ignore) {
                hasOptionals = Boolean.FALSE;
                optAllCompleted = Boolean.FALSE;
                optExcUsd = BigDecimal.ZERO;
                optAssistUsd = BigDecimal.ZERO;
                optEquipUsd = BigDecimal.ZERO;
                optTotalUsd = BigDecimal.ZERO;
            }
        }

        String effectiveStatus;
        try {
            // Operación INDIVIDUAL sin pagos registrados → estado "En cotización" (pre-pago).
            // Se calcula en runtime para no depender de persistir un nuevo valor ENUM en la DB.
            boolean isIndividual = g.getTravelStartDate() != null;
            boolean hasNoPayments = allPaymentRecords.isEmpty();
            boolean notConfirmed = !g.isOperationConfirmed();
            if (isIndividual && hasNoPayments && notConfirmed) {
                effectiveStatus = GroupStatus.EN_COTIZACION.name();
            } else if (shouldApplyOperationalFlow(g)) {
                effectiveStatus = resolveOperationalStatus(g.isOperationConfirmed(), groupPaymentsConciliated).name();
            } else {
                effectiveStatus = g.getStatus() != null ? g.getStatus().name() : null;
            }
        } catch (Exception ignored) {
            effectiveStatus = g.getStatus() != null ? g.getStatus().name() : null;
        }

        return GroupSummaryDto.builder()
                .id(g.getId())
                .sellerId(g.getSellerUserId())
                .destination(g.getDestination())
                .status(effectiveStatus)
                .createdAt(g.getCreatedAt() != null ? g.getCreatedAt().toString() : null)
                .memberCount(members.size())
                .members(members)
                .travelDate(g.getTravelDateLabel())          // ya persistido
                .commonPrefs(g.getCommonPrefs())             // ya persistido
                .departureMonth(g.getDepartureMonth())
                .travelStartDate(
                        g.getTravelStartDate() != null ? g.getTravelStartDate().toString() : null
                )
                .travelEndDate(resolveGroupTravelEndDate(g, reqMembers))
                .departureYear(g.getDepartureYear())
                .autoSearchEnabled(g.isAutoSearchEnabled())
                .quotedTotal(computeQuotedTotal(g.getId()))
                .totalVenta(totalVenta)
                .totalToCharge(groupTotalToCharge)
                .paymentsVerified(groupPaymentsVerified)
                .operationConfirmed(g.isOperationConfirmed())
                .hasOptionals(hasOptionals)
                .optionalsTotalUsd(optTotalUsd)
                .optionalsTotalExcUsd(optExcUsd)
                .optionalsTotalAssistUsd(optAssistUsd)
                .optionalsTotalEquipUsd(optEquipUsd)
                .optionalsAllCompleted(optAllCompleted)
                .individual(g.getTravelStartDate() != null)
                .build();
    }

    /**
     * Resuelve la fecha de fin del viaje para el grupo.
     * Primero intenta travel_group.travel_end_date.
     * Si es null, fallback a la primera travel_request.travel_end_date no-nula del grupo.
     */
    private String resolveGroupTravelEndDate(TravelGroup g, java.util.List<TravelRequest> members) {
        if (g.getTravelEndDate() != null) {
            return g.getTravelEndDate().toString();
        }
        if (members != null) {
            return members.stream()
                    .filter(r -> r != null && r.getTravelEndDate() != null)
                    .map(r -> r.getTravelEndDate().toString())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private Long readPaymentTitularMemberId(TravelGroup g) {
        if (g == null) return null;
        java.util.Map<String, String> prefs = g.getCommonPrefs();
        if (prefs == null) return null;
        String v = prefs.get("paymentTitularMemberId");
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BigDecimal safeBig(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }


    @Transactional
    public GroupSummaryDto updateOperationConfirmed(Long id, boolean confirmed) {
        TravelGroup group = groupRepo.findById(id).orElseThrow();
        group.setOperationConfirmed(confirmed);

        boolean paymentsConciliated = false;
        try {
            List<TravelRequest> members = requestRepo.findByGroupId(group.getId());
            paymentsConciliated = computeGroupPaymentsConciliated(group.getId(), members);
        } catch (Exception ignored) {
        }

        try {
            if (shouldApplyOperationalFlow(group)) {
                group.setStatus(resolveOperationalStatus(confirmed, paymentsConciliated));
            }
        } catch (Exception ignored) {
        }
        groupRepo.save(group);
        if (confirmed) {
            operationConfirmedEmailService.sendConfirmationEmail(group);
        }
        return toDto(group);
    }

    @Transactional
    public GroupSummaryDto updateAutoSearch(Long id, boolean enabled) {
        TravelGroup group = groupRepo.findById(id).orElseThrow();

        group.setAutoSearchEnabled(enabled);

        // Cada vez que el operador activa la búsqueda,
        // arrancamos de cero el contador de agregados automáticos.
        if (enabled) {
            group.setAutoSearchAdded(0);
        }

        groupRepo.save(group);
        return toDto(group);
    }




    @Transactional
    public GroupSummaryDto updateDates(Long groupId, UpdateGroupDatesRequest body) {
        var g = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

        g.setDepartureMonth(body.getDepartureMonth());
        g.setDepartureYear(body.getDepartureYear());

        LocalDate start = parseDate(body.getTravelStartDate());
        LocalDate end   = parseDate(body.getTravelEndDate());

        // validación: hasta >= desde
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("La fecha 'Hasta' no puede ser menor que la fecha 'Desde'.");
        }

        g.setTravelStartDate(start);
        g.setTravelEndDate(end);

        groupRepo.save(g);
        return toDto(g);
    }


    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    private static LocalDate parseLocalDateSafe(String raw) {
        if (raw == null || raw.trim().isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (Exception e) { return null; }
    }

    private static LocalDate parseFlexibleLocalDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // ISO local date: 2026-03-05
        try {
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(s);
            }
        } catch (Exception ignored) {
        }

        // dd/MM/yyyy or d/M/yyyy
        try {
            if (s.matches("^\\d{1,2}/\\d{1,2}/\\d{4}$")) {
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("d/M/uuuu"));
            }
        } catch (Exception ignored) {
        }

        // ISO date-time with offset
        try {
            return java.time.OffsetDateTime.parse(s).toLocalDate();
        } catch (Exception ignored) {
        }

        // ISO instant
        try {
            return java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (Exception ignored) {
        }

        // ISO local date-time
        try {
            return java.time.LocalDateTime.parse(s).toLocalDate();
        } catch (Exception ignored) {
        }

        return null;
    }

    private void unifyPassengerAirDepartureDatesIfNeeded(List<TravelRequest> members, LocalDate chosenDate) {
        if (members == null || members.isEmpty() || chosenDate == null) return;

        List<Long> ids = members.stream()
                .filter(Objects::nonNull)
                .map(TravelRequest::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;

        List<TravelRequestAirService> airSvcs = travelRequestAirServiceRepo.findAllByRequest_IdIn(ids);
        if (airSvcs == null || airSvcs.isEmpty()) return;

        List<LocalDate> dates = airSvcs.stream()
                .map(TravelRequestAirService::getDepartureDate)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (dates.size() <= 1) return;

        // Solo permitir elegir entre las fechas existentes (modal del front)
        if (!dates.contains(chosenDate)) {
            throw new BadRequestException("La fecha seleccionada no coincide con las fechas de viaje de los pasajeros seleccionados.");
        }

        // Si hay aéreos emitidos, no permitir unificar (usar validación existente del flujo de aéreos)
        for (TravelRequestAirService as : airSvcs) {
            if (as == null) continue;
            String rc = as.getReservationCode();
            boolean emitted = rc != null && !rc.trim().isEmpty();
            if (emitted) {
                throw new BadRequestException("No se puede unificar la fecha de viaje porque hay aéreos emitidos en alguno de los pasajeros seleccionados.");
            }
        }

        boolean anyChange = false;
        for (TravelRequestAirService as : airSvcs) {
            if (as == null) continue;
            LocalDate d = as.getDepartureDate();
            if (d != null && !d.equals(chosenDate)) {
                as.setDepartureDate(chosenDate);
                anyChange = true;
            }
        }

        if (anyChange) {
            travelRequestAirServiceRepo.saveAll(airSvcs);
        }
    }

    private void handleGrupalTravelDateSelection(List<TravelRequest> members, String selectedTravelDateRaw) {
        if (members == null || members.isEmpty()) return;

        // Si alguna request tiene travel_start_date => es INDIVIDUAL (no aplica este flujo)
        boolean anyIndividual = members.stream()
                .map(TravelRequest::getTravelStartDate)
                .anyMatch(Objects::nonNull);
        if (anyIndividual) return;

        List<Long> ids = members.stream()
                .filter(Objects::nonNull)
                .map(TravelRequest::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;

        List<TravelRequestAirService> airSvcs = travelRequestAirServiceRepo.findAllByRequest_IdIn(ids);
        if (airSvcs == null || airSvcs.isEmpty()) return;

        List<LocalDate> dates = airSvcs.stream()
                .map(TravelRequestAirService::getDepartureDate)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // Si no hay discrepancia, no hacer nada.
        if (dates.size() <= 1) return;

        // Si hay aéreos emitidos, no permitir unificar.
        for (TravelRequestAirService as : airSvcs) {
            if (as == null) continue;
            String rc = as.getReservationCode();
            if (rc != null && !rc.trim().isEmpty()) {
                throw new BadRequestException("No se puede crear el grupo porque hay aéreos emitidos en alguno de los pasajeros seleccionados.");
            }
        }

        LocalDate chosen = parseFlexibleLocalDate(selectedTravelDateRaw);
        if (chosen == null) {
            throw new BadRequestException("Hay distintas fechas de viaje entre los pasajeros seleccionados. Seleccioná una fecha para crear el grupo.");
        }

        unifyPassengerAirDepartureDatesIfNeeded(members, chosen);
    }


    private GroupMemberDto toMember(TravelRequest r, Boolean paymentOk, BigDecimal planTotalAmount) {
        String fullName = r.getName() != null ? r.getName().trim() : null;
        Boolean depositPaid = r.getDepositAmount() != null && r.getDepositAmount().compareTo(BigDecimal.ZERO) > 0;
        Boolean paymentOkSafe = paymentOk != null ? paymentOk : Boolean.FALSE;
        // quotedTotal para columna "Venta": usa plan.totalAmount si está configurado, sino null (→ "-" en frontend)
        BigDecimal quotedTotal = (planTotalAmount != null && planTotalAmount.compareTo(BigDecimal.ZERO) > 0)
                ? planTotalAmount
                : null;
        return new GroupMemberDto(r.getId(), fullName, r.getEmail(), depositPaid, paymentOkSafe, quotedTotal, r.getAgeMin());
    }


    /**
     * paymentOk: true if the member has a valid payment plan AND all registered payments
     * for that plan are verified in conciliation (status VERIFIED).
     *
     * Nota: no exige cuotas futuras (no pagadas) para OWN_FINANCING; solo verifica lo registrado.
     */
    private static boolean isMemberPaymentsVerified(
            MemberPaymentPlan plan,
            java.util.List<MemberPaymentRecord> records,
            java.util.Map<Long, FinancialMovementConciliation> conciliationByRecordId
    ) {
        if (plan == null) return false;

        // Must have a configured plan first (plan type, amounts, dates, etc.)
        if (!MemberPaymentsAdminService.isPaymentComplete(plan)) return false;

        // Must have at least one registered payment for this member
        if (records == null || records.isEmpty()) return false;

        // Consider the member "payments verified" if ALL registered payments for the plan
        // are conciliated as VERIFIED (Check Panel). We don't require future/unpaid installments.
        Long planId = null;
        try { planId = plan.getId(); } catch (Exception ignore) {}

        int checked = 0;
        for (MemberPaymentRecord r : records) {
            if (r == null || r.getId() == null) continue;

            // Ensure record belongs to this plan (defensive)
            try {
                if (r.getPlan() != null && planId != null && r.getPlan().getId() != null
                        && !r.getPlan().getId().equals(planId)) {
                    continue;
                }
            } catch (Exception ignore) { /* ignore */ }

            checked++;
            // Require conciliation row present and VERIFIED (confirmado) in Check Panel.
            FinancialMovementConciliation c = conciliationByRecordId != null ? conciliationByRecordId.get(r.getId()) : null;
            if (c == null || c.getStatus() != ConciliationStatus.VERIFIED) return false;
        }

        return checked > 0;
    }


    /**
     * expenseOk: true if the member has at least one Expense linked to the group (identified by notes "pasajeroId=<id>")
     * AND all those expenses are conciliated as VERIFIED (Check Panel).
     */
    private static boolean isMemberExpensesVerified(
            java.util.List<app.coincidir.api.domain.expense.Expense> expenses,
            java.util.Map<Long, FinancialMovementConciliation> conciliationByExpenseId
    ) {
        if (expenses == null || expenses.isEmpty()) return false;

        int checked = 0;
        for (app.coincidir.api.domain.expense.Expense e : expenses) {
            if (e == null || e.getId() == null) continue;
            checked++;
            FinancialMovementConciliation c = conciliationByExpenseId != null ? conciliationByExpenseId.get(e.getId()) : null;
            if (c == null || c.getStatus() != ConciliationStatus.VERIFIED) return false;
        }
        return checked > 0;
    }

    /**
     * Estado operativo del grupo según confirmación de operación + conciliación de pagos.
     */
    private static GroupStatus resolveOperationalStatus(boolean operationConfirmed, boolean paymentsConciliated) {
        if (operationConfirmed) {
            return paymentsConciliated ? GroupStatus.EN_OPERACIONES : GroupStatus.EN_OPERACIONES_SC;
        }
        return paymentsConciliated ? GroupStatus.CONCILIADO : GroupStatus.EN_CONCILIACION;
    }

    /**
     * Aplica el flujo operativo solo para grupos que pertenecen al circuito Admin/Operations.
     */
    private static boolean shouldApplyOperationalFlow(TravelGroup g) {
        if (g == null) return false;
        // EN_COTIZACION: estado inicial antes del primer pago registrado.
        // No se le aplica el flujo operacional para que el DTO devuelva el estado real.
        GroupStatus sCheck = null;
        try { sCheck = g.getStatus(); } catch (Exception ignored) {}
        if (sCheck == GroupStatus.EN_COTIZACION) return false;
        try {
            if (g.isOperationConfirmed()) return true;
        } catch (Exception ignored) {
        }
        try {
            if (g.getSellerUserId() != null) return true;
        } catch (Exception ignored) {
        }
        GroupStatus s = null;
        try {
            s = g.getStatus();
        } catch (Exception ignored) {
        }
        return s == GroupStatus.EN_CONCILIACION
                || s == GroupStatus.CONCILIADO
                || s == GroupStatus.EN_OPERACIONES_SC
                || s == GroupStatus.EN_OPERACIONES
                || s == GroupStatus.PENDIENTE_CONCILIACION;
    }

    private static boolean areAllPaymentRecordsConciliated(
            java.util.List<MemberPaymentRecord> records,
            java.util.Map<Long, FinancialMovementConciliation> conciliationByRecordId
    ) {
        if (records == null || records.isEmpty()) return false;
        int checked = 0;
        for (MemberPaymentRecord r : records) {
            if (r == null || r.getId() == null) continue;
            checked++;
            FinancialMovementConciliation c = conciliationByRecordId != null ? conciliationByRecordId.get(r.getId()) : null;
            if (c == null || c.getStatus() != ConciliationStatus.VERIFIED) return false;
        }
        return checked > 0;
    }

    private static boolean areAllExpensesConciliated(
            java.util.Set<Long> expenseIds,
            java.util.Map<Long, FinancialMovementConciliation> conciliationByExpenseId
    ) {
        if (expenseIds == null || expenseIds.isEmpty()) return false;
        int checked = 0;
        for (Long id : expenseIds) {
            if (id == null) continue;
            checked++;
            FinancialMovementConciliation c = conciliationByExpenseId != null ? conciliationByExpenseId.get(id) : null;
            if (c == null || c.getStatus() != ConciliationStatus.VERIFIED) return false;
        }
        return checked > 0;
    }

    /**
     * Conciliación de pagos a nivel grupo.
     * - Si hay registros en member_payment_record: exige que TODOS estén VERIFIED en financial_movement_conciliation.
     * - Si no hay registros, intenta fallback con gastos temporales (expenses) linkeados a pasajeros.
     */
    private boolean computeGroupPaymentsConciliated(Long groupId, java.util.List<TravelRequest> members) {
        if (groupId == null) return false;

        try {
            var records = paymentRecordRepo.findAllByGroupIdOrderByPaymentDateDescIdDesc(groupId);
            if (records != null && !records.isEmpty()) {
                java.util.Set<Long> recordIds = records.stream()
                        .map(MemberPaymentRecord::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());

                java.util.Map<Long, FinancialMovementConciliation> conciliationByRecordId;
                if (recordIds.isEmpty()) {
                    conciliationByRecordId = java.util.Collections.emptyMap();
                } else {
                    conciliationByRecordId = conciliationRepo
                            .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, recordIds)
                            .stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    FinancialMovementConciliation::getMovementId,
                                    c -> c,
                                    (a, b) -> a
                            ));
                }
                return areAllPaymentRecordsConciliated(records, conciliationByRecordId);
            }
        } catch (Exception ignored) {
        }

        // Fallback: gastos temporales
        if (members == null || members.isEmpty()) return false;
        java.util.Set<Long> expenseIds = new java.util.HashSet<>();
        try {
            for (TravelRequest mbr : members) {
                if (mbr == null || mbr.getId() == null) continue;
                java.util.List<app.coincidir.api.domain.expense.Expense> exps;
                try {
                    exps = expenseRepo.findAllByGroupIdAndNotesContaining(groupId, "pasajeroId=" + mbr.getId());
                } catch (Exception ex) {
                    exps = java.util.List.of();
                }
                if (exps != null) {
                    for (app.coincidir.api.domain.expense.Expense e : exps) {
                        if (e != null && e.getId() != null) expenseIds.add(e.getId());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (expenseIds.isEmpty()) return false;

        try {
            java.util.Map<Long, FinancialMovementConciliation> conciliationByExpenseId = conciliationRepo
                    .findAllByMovementTypeAndMovementIdIn(FinancialMovementType.EXPENSE_RECORD, expenseIds)
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            FinancialMovementConciliation::getMovementId,
                            c -> c,
                            (a, b) -> a
                    ));

            return areAllExpensesConciliated(expenseIds, conciliationByExpenseId);
        } catch (Exception ignored) {
            return false;
        }
    }

    /* -------------------- Summary helpers -------------------- */

    /** Si no hay resumen calculado, lo genera y guarda. */
    private void ensureSummary(TravelGroup g) {
        boolean missing = (g.getTravelDateLabel() == null)
                || (g.getCommonPrefs() == null || g.getCommonPrefs().isEmpty());
        if (missing) {
            refreshSummary(g);
            groupRepo.save(g);
        }
    }

    /** Calcula travelDateLabel y commonPrefs a partir de los miembros. */
    private void refreshSummary(TravelGroup g) {
        var members = g.getMembers();
        if (members == null || members.isEmpty()) {
            g.setTravelDateLabel(null);
            g.setCommonPrefs(null);
            return;
        }

        // Label de fecha: mayoría del whenLabel
        String label = members.stream()
                .map(TravelRequest::getWhenLabel)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        g.setTravelDateLabel(label);

        // Prefs comunes mostradas en el popup
        Map<String, String> prefs = new java.util.LinkedHashMap<>();
        putIfPresent(prefs, "Destino", majority(members, TravelRequest::getDestination));
        putIfPresent(prefs, "Compañía", majority(members, r -> safe(r.getCompanionPreference())));
        putIfPresent(prefs, "Rango edades", ageRange(members));
        putIfPresent(prefs, "Smoke free", majorityBool(members, GroupAdminService::smokeFreeSafe));
        putIfPresent(prefs, "Pax", paxRange(members));

        g.setCommonPrefs(prefs);
    }

    @Transactional
    public GroupSummaryDto addMembers(Long groupId, List<Long> requestIds) {
        // Si no hay nada que agregar, devolver el grupo tal cual está
        if (requestIds == null || requestIds.isEmpty()) {
            TravelGroup g = groupRepo.findById(groupId).orElseThrow();
            ensureSummary(g);
            groupRepo.save(g);
            return toDto(g);
        }

        // 1) Traer el grupo
        TravelGroup group = groupRepo.findById(groupId).orElseThrow();

        // 2) Traer las solicitudes a agregar
        List<TravelRequest> requests = requestRepo.findAllById(requestIds);

        // 3) Asignar sólo las que estén libres y en NEW
        for (TravelRequest r : requests) {
            if (r.getGroup() == null &&
                    r.getStatus() == app.coincidir.api.domain.RequestStatus.NEW) {

                r.setGroup(group);
                r.setStatus(app.coincidir.api.domain.RequestStatus.GROUPED);
                requestRepo.save(r);
                paymentsBootstrapService.bootstrapFromRequestIfNeeded(r);
            }
        }

        // 4) Sincronizar con la base y recargar el grupo con sus miembros ya actualizados
        em.flush();
        em.clear();  // vaciamos el contexto de persistencia para evitar que quede el grupo “viejo”

        TravelGroup refreshed = groupRepo.findById(groupId).orElseThrow();

        // 5) Recalcular el resumen con los miembros nuevos
        refreshSummary(refreshed);
        groupRepo.save(refreshed);

        // 6) Devolver el DTO ya con la lista de miembros actualizada
        return toDto(refreshed);
    }

    @Transactional
    public GroupSummaryDto generateGroupFromRequests(List<Long> requestIds, Long seedRequestId, String selectedTravelDate, Long paymentTitularMemberId, String createdByEmail) {
        return generateGroupFromRequests(requestIds, seedRequestId, selectedTravelDate, paymentTitularMemberId, createdByEmail, null);
    }

    @Transactional
    public GroupSummaryDto generateGroupFromRequests(List<Long> requestIds, Long seedRequestId, String selectedTravelDate, Long paymentTitularMemberId, String createdByEmail, Long forcedId) {
        if (requestIds == null || requestIds.isEmpty()) {
            throw new BadRequestException("Tenés que seleccionar al menos 1 pasajero para generar un grupo.");
        }

        // Traer solicitudes y validar que estén libres
        List<TravelRequest> requests = requestRepo.findAllById(requestIds).stream()
                .filter(Objects::nonNull)
                .toList();

        List<TravelRequest> available = requests.stream()
                .filter(r -> r.getGroup() == null)
                .toList();

        if (available.isEmpty()) {
            throw new BadRequestException("Los pasajeros seleccionados no están disponibles para generar un grupo.");
        }

        TravelRequest seed = available.get(0);
        if (seedRequestId != null) {
            for (TravelRequest r : available) {
                if (seedRequestId.equals(r.getId())) {
                    seed = r;
                    break;
                }
            }
        }

        String dest = seed.getDestination();
        String when = seed.getWhenLabel();
        String compPref = seed.getCompanionPreference();

        // Caso Grupal: si hay distintas fechas de viaje entre los seleccionados, exigir una fecha
        // y unificarla para todos (solo si no hay aéreos emitidos).
        try {
            handleGrupalTravelDateSelection(available, selectedTravelDate);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception ignored) {
            // noop (best-effort)
        }


        // Si se está generando un grupo con pasajeros de distintos destinos,
        // validar que ninguno tenga Aéreos ya emitidos (reserva cargada) para un destino distinto.
        try {
            long distinctDests = available.stream()
                    .map(TravelRequest::getDestination)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .distinct()
                    .count();

            if (distinctDests > 1) {
                List<Long> ids = available.stream()
                        .map(TravelRequest::getId)
                        .filter(Objects::nonNull)
                        .toList();

                var airSvcs = travelRequestAirServiceRepo.findAllByRequest_IdIn(ids);
                java.util.Map<Long, TravelRequestAirService> airByRequestId = airSvcs.stream()
                        .filter(Objects::nonNull)
                        .filter(a -> a.getRequest() != null && a.getRequest().getId() != null)
                        .collect(Collectors.toMap(a -> a.getRequest().getId(), a -> a, (a, b) -> a));

                for (TravelRequest r : available) {
                    if (r == null || r.getId() == null) continue;
                    TravelRequestAirService as = airByRequestId.get(r.getId());
                    boolean emitted = as != null && as.getReservationCode() != null && !as.getReservationCode().trim().isEmpty();
                    if (!emitted) continue;

                    String rd = r.getDestination();
                    if (rd != null && dest != null && !rd.trim().equalsIgnoreCase(dest.trim())) {
                        String passengerName = r.getName() != null ? r.getName().trim() : "";
                        String passengerDni = r.getDni() != null ? r.getDni().trim() : "";
                        String who = passengerName.isEmpty() ? ("ID " + r.getId()) : passengerName;
                        if (!passengerDni.isEmpty()) who = who + " (DNI " + passengerDni + ")";
                        String currentDest = rd != null ? rd.trim() : "";
                        throw new BadRequestException("No se puede crear el grupo: el pasajero " + who + " ya tiene emitido el aéreo y su destino actual es '" + currentDest + "'.");
                    }
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception ignored) {
            // noop
        }
        boolean smoke = smokeFreeSafe(seed);
        Integer ageMin = seed.getAgeMin();
        Integer ageMax = seed.getAgeMax();

        String ageBucket = (ageMin != null && ageMax != null)
                ? ageMin + "-" + ageMax
                : ageBucketFallback(ageMin, ageMax);

        // Determinar tipo de operación según la carga:
        // - INDIVIDUAL: la TravelRequest trae travel_start_date
        // - GROUP/GRUPAL: travel_start_date es null (usa mes/aéreos)
        java.util.List<java.time.LocalDate> individualDates = available.stream()
                .map(TravelRequest::getTravelStartDate)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        java.time.LocalDate groupTravelStart = individualDates.stream()
                .min(java.time.LocalDate::compareTo)
                .orElse(null);

        // Caso INDIVIDUAL: si hay distintas fechas, exigir selección (y validar que no haya aéreos emitidos).
        if (groupTravelStart != null && individualDates.size() > 1) {
            try {
                java.util.List<Long> ids = available.stream()
                        .map(TravelRequest::getId)
                        .filter(java.util.Objects::nonNull)
                        .toList();

                if (!ids.isEmpty()) {
                    var airSvcs = travelRequestAirServiceRepo.findAllByRequest_IdIn(ids);
                    for (TravelRequestAirService as : airSvcs) {
                        if (as == null) continue;
                        String rc = as.getReservationCode();
                        if (rc != null && !rc.trim().isEmpty()) {
                            throw new BadRequestException("No se puede crear la operación porque hay aéreos emitidos en alguno de los pasajeros seleccionados.");
                        }
                    }
                }
            } catch (BadRequestException e) {
                throw e;
            } catch (Exception ignored) {
                // noop
            }

            java.time.LocalDate chosen = parseFlexibleLocalDate(selectedTravelDate);
            if (chosen == null) {
                throw new BadRequestException("Hay distintas fechas de viaje entre los pasajeros seleccionados. Seleccioná una fecha para crear la operación.");
            }
            if (!individualDates.contains(chosen)) {
                throw new BadRequestException("La fecha seleccionada no coincide con las fechas de viaje de los pasajeros seleccionados.");
            }
            groupTravelStart = chosen;
        }

        boolean isIndividual = groupTravelStart != null;

        Long resolvedPaymentTitularMemberId = null;
        if (paymentTitularMemberId != null) {
            for (TravelRequest r : available) {
                if (r != null && r.getId() != null && r.getId().equals(paymentTitularMemberId)) {
                    resolvedPaymentTitularMemberId = r.getId();
                    break;
                }
            }
        }
        if (resolvedPaymentTitularMemberId == null && seed != null && seed.getId() != null) {
            resolvedPaymentTitularMemberId = seed.getId();
        }

        Long sellerId = tryResolveSellerId(createdByEmail);

        TravelGroup group = TravelGroup.builder()
                .status(GroupStatus.FORMED)
                .destination(dest)
                .whenLabel(when)
                .travelDateLabel(when)
                .companionPreference(compPref)
                .smokeFree(smoke)
                .sizeTarget(9)
                .ageBucket(ageBucket)
                .createdAt(Instant.now())
                .autoSearchEnabled(false)
                .sellerUserId(sellerId)
                // Para operaciones INDIVIDUALES, persistimos la fecha concreta para separar/filtrar en el panel de grupos.
                .travelStartDate(isIndividual ? groupTravelStart : null)
                .build();

        if (isIndividual && resolvedPaymentTitularMemberId != null) {
            java.util.Map<String, String> commonPrefs = group.getCommonPrefs();
            if (commonPrefs == null) commonPrefs = new java.util.LinkedHashMap<>();
            commonPrefs.put("paymentTitularMemberId", String.valueOf(resolvedPaymentTitularMemberId));
            group.setCommonPrefs(commonPrefs);
        }

        groupRepo.save(group);

        // Si se indicó un ID forzado (parámetro FORZAR_NRO_OPERACION activo), reasignar el ID
        // antes de asociar cualquier FK. En este punto ninguna otra tabla apunta aún a este registro.
        if (forcedId != null && forcedId > 0) {
            // 1) Flush: JPA persiste el INSERT con el ID autoincremental.
            // 2) Clear: desvincula TODAS las entidades del contexto para que Hibernate no intente
            //    sincronizar la entidad cuyo ID estamos a punto de cambiar ("identifier altered").
            em.flush();
            em.clear();
            Long autoId = group.getId();
            try {
                int updated = jdbc.update("UPDATE travel_group SET id = ? WHERE id = ?", forcedId, autoId);
                if (updated != 1) {
                    throw new BadRequestException("No se pudo reasignar el número de operación " + forcedId + ".");
                }
            } catch (BadRequestException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BadRequestException("El número de operación " + forcedId + " ya existe o no es válido.");
            }
            // 3) Re-cargar la entidad desde DB con el nuevo ID para que todas las operaciones
            //    JPA posteriores usen un objeto correctamente gestionado (evita "detached entity").
            group = groupRepo.findById(forcedId)
                    .orElseThrow(() -> new BadRequestException("No se encontró la operación " + forcedId + " tras la reasignación."));
        }
        // Si no existe registro, el panel lo interpreta como false y se crea on-demand
        // al primer toggle (OperationsService). Evitamos que un fallo en esa tabla
        // rompa la generación del grupo.

        for (TravelRequest r : available) {
            r.setGroup(group);
            r.setStatus(RequestStatus.GROUPED);
            requestRepo.save(r);

            // Al crear grupo desde Listado de Pasajeros, si el pasajero ya tenía pagos materializados
            // como "ungrouped" (carga manual), migrarlos al group_id real para que se reflejen en Detalle.
            // Si no existían, bootstrappear desde deposit_* (best-effort).
            try {
                paymentsBootstrapService.bootstrapFromRequestIfNeeded(r);
            } catch (Exception ignored) {
            }
        }

        // Al generar el grupo desde Listado de pasajeros:
        // - Asociar gastos temporales (Aéreos) al group_id real (sin tocar member_payment_*)
        // - Marcar el servicio Aéreos como EMITIDO y persistir group_id en group_air_service
        attachUngroupedMemberPaymentsToGroup(group, available);
        bootstrapAirServicesForGroup(group, available, seed);

        em.flush();
        em.clear();

        TravelGroup refreshed = groupRepo.fetchDetail(group.getId()).orElseThrow();
        refreshSummary(refreshed);
        groupRepo.save(refreshed);

        return toDto(refreshed);
    }

    public boolean isForzarNroOperacionActive() {
        try {
            String val = jdbc.queryForObject(
                    "SELECT value FROM parametros WHERE activo = 1 AND code = 'FORZAR_NRO_OPERACION' LIMIT 1",
                    String.class
            );
            return "1".equals(val) || "true".equalsIgnoreCase(val) || "SI".equalsIgnoreCase(val);
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean groupExistsById(Long id) {
        if (id == null || id <= 0) return false;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM travel_group WHERE id = ?",
                    Integer.class,
                    id
            );
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String ageBucketFallback(Integer min, Integer max) {
        int a = (min == null) ? 18 : min;
        int b = (max == null) ? a : max;
        int start = (a / 5) * 5;
        int end = (b / 5) * 5 + (b % 5 == 0 ? 0 : 4);
        return start + "-" + end;
    }


    private Long getOrCreateTempGroupId() {
        // Evitar duplicados históricos: si existen varios __TEMP_PAYMENTS__, usar el de menor id.
        try {
            List<TravelGroup> existing = groupRepo.findAllByDestinationOrdered(TEMP_GROUP_DEST);
            if (existing != null && !existing.isEmpty()) {
                TravelGroup g = existing.get(0);
                // normalizar etiquetas si faltan (best-effort)
                if (g.getWhenLabel() == null || g.getWhenLabel().isBlank()) g.setWhenLabel(TEMP_GROUP_WHEN);
                if (g.getTravelDateLabel() == null || g.getTravelDateLabel().isBlank()) g.setTravelDateLabel(TEMP_GROUP_WHEN);
                groupRepo.save(g);
                return g.getId();
            }
        } catch (Exception ignored) {
        }

        return groupRepo.save(TravelGroup.builder()
                        .status(GroupStatus.OPEN)
                        .destination(TEMP_GROUP_DEST)
                        .whenLabel(TEMP_GROUP_WHEN)
                        .travelDateLabel(TEMP_GROUP_WHEN)
                        .companionPreference("ANY")
                        .smokeFree(false)
                        .sizeTarget(1)
                        .createdAt(Instant.now())
                        .autoSearchEnabled(false)
                        .operationConfirmed(false)
                        .build())
                .getId();
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

    @Transactional
    void attachUngroupedMemberPaymentsToGroup(TravelGroup group, List<TravelRequest> members) {
        // En el flujo "Listado de Pasajeros" (Crear grupo), la carga de pagos de Aéreos es
        // temporal y se persiste SOLO en expenses (para conciliación). Al crear el grupo real,
        // migramos esos expenses desde el grupo temporal al nuevo group_id, sin tocar member_payment_*.
        if (group == null || group.getId() == null || members == null || members.isEmpty()) return;

        final Long groupId = group.getId();

        // Importante: NO crear el grupo temporal como efecto colateral al crear un grupo real.
        // Solo migramos si ya existe algún __TEMP_PAYMENTS__.
        final List<Long> tempGroupIds = findExistingTempGroupIds();
        if (tempGroupIds.isEmpty()) return;

        for (TravelRequest m : members) {
            if (m == null || m.getId() == null) continue;
            for (Long tempGroupId : tempGroupIds) {
                migrateTempExpensesToGroup(tempGroupId, groupId, m.getId());
            }
        }
    }


    @Transactional
    void migrateTempExpensesToGroup(Long tempGroupId, Long newGroupId, Long memberId) {
        if (tempGroupId == null || newGroupId == null || memberId == null) return;
        var exps = expenseRepo.findAllByGroupIdAndNotesContaining(tempGroupId, "pasajeroId=" + memberId);
        if (exps == null || exps.isEmpty()) return;
        for (var e : exps) {
            if (e == null) continue;
            e.setGroupId(newGroupId);
        }
        expenseRepo.saveAll(exps);
    }

    @Transactional
    void bootstrapAirServicesForGroup(TravelGroup group, List<TravelRequest> members, TravelRequest seed) {
        if (group == null || group.getId() == null || members == null || members.isEmpty()) return;

        // Soporte multi-aéreos solo cuando vienen precargados desde carga manual (JSON en depositNotes)
        // y el destino es "Ushuaia + Calafate".
        List<AirLeg> legs = readAirLegsFromDepositNotes(seed == null ? null : seed.getDepositNotes());
        String dest = seed != null ? seed.getDestination() : group.getDestination();
        if (isUshuaiaCalafate(dest) && legs.size() >= 2) {
            bootstrapMultiAirServices(group, members, legs);
            return;
        }

        // Fallback legacy (1 solo aéreo)
        bootstrapAirServiceAsEmitted(group, members);
    }

    private boolean isUshuaiaCalafate(String destination) {
        if (destination == null) return false;
        String n = destination.trim().toLowerCase();
        // tolerante a espacios
        n = n.replace("  ", " ");
        return n.equals("ushuaia + calafate") || n.equals("ushuaia+ calafate") || n.equals("ushuaia +calafate") || n.equals("ushuaia+calafate");
    }

    private List<AirLeg> readAirLegsFromDepositNotes(String depositNotes) {
        if (depositNotes == null || depositNotes.isBlank()) return java.util.Collections.emptyList();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(depositNotes);
            JsonNode arr = root == null ? null : root.get("airServices");
            if (arr == null || !arr.isArray()) return java.util.Collections.emptyList();

            List<AirLeg> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n == null || !n.isObject()) continue;
                AirLeg leg = new AirLeg();
                leg.tripType = text(n, "tripType");
                leg.origin = text(n, "origin");
                leg.destination = text(n, "destination");
                leg.airline = text(n, "airline");
                leg.departureDate = parseDate(text(n, "departureDate"));
                leg.departureTime = parseTime(text(n, "departureTime"));
                leg.departureArrivalTime = parseTime(text(n, "departureArrivalTime"));
                leg.returnDate = parseDate(text(n, "returnDate"));
                leg.returnDepartureTime = parseTime(text(n, "returnDepartureTime"));
                leg.returnArrivalTime = parseTime(text(n, "returnArrivalTime"));
                leg.baggageAllowance = text(n, "baggageAllowance");

                boolean any = (leg.origin != null && !leg.origin.isBlank())
                        || (leg.destination != null && !leg.destination.isBlank())
                        || (leg.airline != null && !leg.airline.isBlank())
                        || leg.departureDate != null
                        || leg.departureTime != null;

                if (!any) continue;

                if (leg.tripType == null || leg.tripType.isBlank()) {
                    leg.tripType = (leg.returnDate != null ? "ROUND_TRIP" : "ONE_WAY");
                } else {
                    leg.tripType = leg.tripType.trim().toUpperCase();
                    if (!leg.tripType.equals("ONE_WAY") && !leg.tripType.equals("ROUND_TRIP")) {
                        leg.tripType = (leg.returnDate != null ? "ROUND_TRIP" : "ONE_WAY");
                    }
                }
                if (leg.baggageAllowance == null || leg.baggageAllowance.isBlank()) {
                    leg.baggageAllowance = "6_KG";
                }

                out.add(leg);
            }

            return out;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null) return null;
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String t = v.asText(null);
        if (t == null) return null;
        t = t.trim();
        return t.isBlank() ? null : t;
    }
    private static LocalTime parseTime(String iso) {
        try {
            return (iso == null || iso.isBlank()) ? null : LocalTime.parse(iso.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static class AirLeg {
        String tripType;
        String origin;
        String destination;
        String airline;
        LocalDate departureDate;
        LocalTime departureTime;
        LocalTime departureArrivalTime;
        LocalDate returnDate;
        LocalTime returnDepartureTime;
        LocalTime returnArrivalTime;
        String baggageAllowance;
    }

    @Transactional
    void bootstrapMultiAirServices(TravelGroup group, List<TravelRequest> members, List<AirLeg> legs) {
        if (group == null || group.getId() == null || members == null || members.isEmpty() || legs == null || legs.isEmpty()) return;

        // Asegurar ServiceDefinition
        ServiceDefinition svc = serviceDefinitionRepo.findByCode(ServiceCode.AEREOS)
                .orElseThrow(() -> new NotFoundException("ServiceDefinition AEREOS no encontrada"));

        // Buscar items existentes de AEREOS (si ya hubiera uno) y completar hasta 3
        List<GroupServiceMenuItem> all = groupServiceMenuItemRepo.findByGroupIdOrderByPositionAsc(group.getId());
        List<GroupServiceMenuItem> existing = all.stream()
                .filter(i -> i != null && i.getService() != null && i.getService().getCode() == ServiceCode.AEREOS)
                .toList();

        int desired = Math.min(3, legs.size());
        List<GroupServiceMenuItem> items = new ArrayList<>();
        items.addAll(existing);

        int nextPos = groupServiceMenuItemRepo.findMaxPosition(group.getId()) + 1;
        while (items.size() < desired) {
            GroupServiceMenuItem item = new GroupServiceMenuItem();
            item.setGroup(group);
            item.setService(svc);
            item.setDisplayName(svc.getName());
            item.setPosition(nextPos++);
            item = groupServiceMenuItemRepo.save(item);
            items.add(item);
        }

        // Configurar cada aéreo (Aéreo 1/2/3)
        for (int idx = 0; idx < desired; idx++) {
            GroupServiceMenuItem menuItem = items.get(idx);
            AirLeg leg = legs.get(idx);

            menuItem.setDisplayName("Aéreo " + (idx + 1));
            menuItem.setOperationStatus(OperationStatusCode.PENDIENTE);
            menuItem.setOperationStatusUpdatedAt(Instant.now());
            groupServiceMenuItemRepo.save(menuItem);

            GroupAirService air = groupAirServiceRepo.findByMenuItemId(menuItem.getId()).orElseGet(GroupAirService::new);
            air.setMenuItem(menuItem);
            air.setGroupId(group.getId());

            air.setTripType(leg.tripType);
            air.setOrigin(leg.origin);
            air.setDestination(leg.destination);
            air.setAirline(leg.airline);
            air.setDepartureDate(leg.departureDate);
            air.setDepartureTime(leg.departureTime);
            air.setDepartureArrivalTime(leg.departureArrivalTime);
            air.setReturnDate(leg.returnDate);
            air.setReturnDepartureTime(leg.returnDepartureTime);
            air.setReturnArrivalTime(leg.returnArrivalTime);
            air.setBaggageAllowance(leg.baggageAllowance);

            GroupAirService saved = groupAirServiceRepo.save(air);

            // Sincronizar a nivel miembro
            for (TravelRequest m : members) {
                if (m == null || m.getId() == null) continue;
                var ms = memberAirRepo.findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                        .orElseGet(app.coincidir.api.domain.MemberAirService::new);
                ms.setMenuItem(menuItem);
                ms.setMember(m);
                ms.setOverridden(false);
                ms.setTripType(saved.getTripType());
                ms.setOrigin(saved.getOrigin());
                ms.setDestination(saved.getDestination());
                ms.setAirline(saved.getAirline());
                ms.setDepartureDate(saved.getDepartureDate());
                ms.setDepartureTime(saved.getDepartureTime());
                ms.setDepartureArrivalTime(saved.getDepartureArrivalTime());
                ms.setReturnDate(saved.getReturnDate());
                ms.setReturnDepartureTime(saved.getReturnDepartureTime());
                ms.setReturnArrivalTime(saved.getReturnArrivalTime());
                ms.setBaggageAllowance(saved.getBaggageAllowance());
                ms.setReservationCode(saved.getReservationCode());
                ms.setQuotedValue(saved.getQuotedValue());
                ms.setQuotedAt(saved.getQuotedAt());
                memberAirRepo.save(ms);
            }
        }
    }

    @Transactional
    void bootstrapAirServiceAsEmitted(TravelGroup group, List<TravelRequest> members) {
        if (group == null || group.getId() == null || members == null || members.isEmpty()) return;

        List<Long> memberIds = members.stream().filter(Objects::nonNull).map(TravelRequest::getId).filter(Objects::nonNull).toList();
        if (memberIds.isEmpty()) return;

        if (!travelRequestAirServiceRepo.existsByRequest_IdIn(memberIds)) {
            return;
        }

        // Tomamos el primer aereo cargado como plantilla (mismo destino/origen en el flujo normal)
        TravelRequestAirService template = travelRequestAirServiceRepo.findAllByRequest_IdIn(memberIds).stream().findFirst().orElse(null);
        if (template == null) return;

        // 1) Asegurar menú AEREOS
        GroupServiceMenuItem menuItem = groupServiceMenuItemRepo
                .findFirstByGroupIdAndService_CodeOrderByPositionAsc(group.getId(), ServiceCode.AEREOS)
                .orElseGet(() -> {
                    ServiceDefinition svc = serviceDefinitionRepo.findByCode(ServiceCode.AEREOS)
                            .orElseThrow(() -> new NotFoundException("ServiceDefinition AEREOS no encontrada"));
                    int nextPos = groupServiceMenuItemRepo.findMaxPosition(group.getId()) + 1;
                    GroupServiceMenuItem item = new GroupServiceMenuItem();
                    item.setGroup(group);
                    item.setService(svc);
                    item.setDisplayName(svc.getName());
                    item.setPosition(nextPos);
                    return groupServiceMenuItemRepo.save(item);
                });

        // 2) Estado de operación inicial: PENDIENTE (carga temprana desde listado de pasajeros)
        menuItem.setOperationStatus(OperationStatusCode.PENDIENTE);
        menuItem.setOperationStatusUpdatedAt(Instant.now());
        groupServiceMenuItemRepo.save(menuItem);

        // 3) Persistir group_air_service con group_id
        GroupAirService air = groupAirServiceRepo.findByMenuItemId(menuItem.getId()).orElseGet(GroupAirService::new);
        air.setMenuItem(menuItem);
        air.setGroupId(group.getId());

        air.setTripType(template.getTripType());
        air.setOrigin(template.getOrigin());
        air.setDestination(template.getDestination());
        air.setAirline(template.getAirline());
        air.setDepartureDate(template.getDepartureDate());
        air.setDepartureTime(template.getDepartureTime());
        air.setDepartureArrivalTime(template.getDepartureArrivalTime());
        air.setReturnDate(template.getReturnDate());
        air.setReturnDepartureTime(template.getReturnDepartureTime());
        air.setReturnArrivalTime(template.getReturnArrivalTime());
        air.setBaggageAllowance(template.getBaggageAllowance());
        air.setReservationCode(template.getReservationCode());
        air.setQuotedValue(template.getQuotedValue());
        air.setQuotedAt(template.getQuotedAt());
        air.setTotalCost(template.getTotalCost());
        air.setTotalCostUpdatedAt(template.getTotalCostUpdatedAt());

        GroupAirService saved = groupAirServiceRepo.save(air);

        // 4) Sincronizar a nivel miembro (MemberAirService)
        for (TravelRequest m : members) {
            if (m == null || m.getId() == null) continue;
            var ms = memberAirRepo.findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                    .orElseGet(app.coincidir.api.domain.MemberAirService::new);
            ms.setMenuItem(menuItem);
            ms.setMember(m);
            ms.setOverridden(false);
            ms.setTripType(saved.getTripType());
            ms.setOrigin(saved.getOrigin());
            ms.setDestination(saved.getDestination());
            ms.setAirline(saved.getAirline());
            ms.setDepartureDate(saved.getDepartureDate());
            ms.setDepartureTime(saved.getDepartureTime());
            ms.setDepartureArrivalTime(saved.getDepartureArrivalTime());
            ms.setReturnDate(saved.getReturnDate());
            ms.setReturnDepartureTime(saved.getReturnDepartureTime());
            ms.setReturnArrivalTime(saved.getReturnArrivalTime());
            ms.setBaggageAllowance(saved.getBaggageAllowance());
            ms.setReservationCode(saved.getReservationCode());
            ms.setQuotedValue(saved.getQuotedValue());
            ms.setQuotedAt(saved.getQuotedAt());
            memberAirRepo.save(ms);
        }
    }

    /**
     * Carga manual: crea una TravelRequest y la asocia al grupo.
     * El email y password son opcionales: si se proveen se crea también el UserAccount.
     */
    @Transactional
    public GroupSummaryDto addManualMember(Long groupId, AddManualMemberRequest body) {
        if (body == null) {
            throw new BadRequestException("Body requerido");
        }

        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado"));

        String firstName = body.firstName() == null ? null : body.firstName().trim();
        String lastName = body.lastName() == null ? null : body.lastName().trim();
        String fullName = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();

        // Email y password son opcionales
        String rawEmail = body.email() == null ? null : body.email().trim();
        String email = (rawEmail != null && !rawEmail.isBlank()) ? rawEmail.toLowerCase() : null;
        String rawPassword = body.password() == null ? null : body.password().trim();

        // Crear UserAccount solo si se proveen credenciales válidas
        if (email != null && rawPassword != null && !rawPassword.isBlank()) {
            if (userRepo.findByEmailIgnoreCase(email).isPresent()) {
                throw new BadRequestException("Ya existe un usuario con ese email");
            }
            UserAccount user = UserAccount.builder()
                    .email(email)
                    .password(encoder.encode(rawPassword))
                    .role("USER")
                    .firstName(firstName)
                    .lastName(lastName)
                    .build();
            userRepo.save(user);
        }

        if (fullName.isBlank() && email != null) fullName = email;

        // --- Defaults requeridos por DB ---
        Integer resolvedAgeMin = null;
        Integer resolvedAgeMax = null;
        Integer resolvedPaxMin = null;
        Integer resolvedPaxMax = null;

        try {
            if (group.getMembers() != null && !group.getMembers().isEmpty()) {
                TravelRequest sample = group.getMembers().get(0);
                resolvedAgeMin = sample.getAgeMin();
                resolvedAgeMax = sample.getAgeMax();
                resolvedPaxMin = sample.getPaxMin();
                resolvedPaxMax = sample.getPaxMax();
            }
        } catch (Exception ignored) {}

        if ((resolvedAgeMin == null || resolvedAgeMax == null) && group.getAgeBucket() != null) {
            String b = group.getAgeBucket().trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*[-\u2013]\\s*(\\d{1,3})").matcher(b);
            if (m.find()) {
                try {
                    int a1 = Integer.parseInt(m.group(1));
                    int a2 = Integer.parseInt(m.group(2));
                    resolvedAgeMin = Math.min(a1, a2);
                    resolvedAgeMax = Math.max(a1, a2);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (resolvedAgeMin == null) resolvedAgeMin = 18;
        if (resolvedAgeMax == null) resolvedAgeMax = 99;
        if (resolvedPaxMin == null) resolvedPaxMin = (group.getSizeTarget() > 0 ? group.getSizeTarget() : 1);

        java.time.LocalDate parsedBirthDate = null;
        if (body.birthDate() != null && !body.birthDate().trim().isBlank()) {
            try { parsedBirthDate = java.time.LocalDate.parse(body.birthDate().trim()); } catch (Exception ignored) {}
        }

        TravelRequest tr = TravelRequest.builder()
                .destination(group.getDestination())
                .whenLabel(group.getWhenLabel())
                .travelStartDate(group.getTravelStartDate())
                .companionPreference(group.getCompanionPreference())
                .smokeFree(group.isSmokeFree())
                .ageMin(resolvedAgeMin)
                .ageMax(resolvedAgeMax)
                .paxMin(resolvedPaxMin)
                .paxMax(resolvedPaxMax)
                .gender(body.gender())
                .name(fullName.isBlank() ? null : fullName)
                .email(email)
                .phone(body.phone())
                .phoneCountryCode(body.phoneCountryCode())
                .dni(body.dni())
                .birthDate(parsedBirthDate)
                .documentType(body.documentType() == null ? null : body.documentType().trim())
                .documentExpiryDate(parseLocalDateSafe(body.documentExpiryDate()))
                .documentNoExpiry(Boolean.TRUE.equals(body.documentNoExpiry()))
                .documentNotApplicable(Boolean.TRUE.equals(body.documentNotApplicable()))
                .countryId(body.countryId())
                .country(body.countryName() == null ? null : body.countryName().trim())
                .status(RequestStatus.GROUPED)
                .group(group)
                .build();
        requestRepo.save(tr);

        em.flush();
        em.clear();

        TravelGroup refreshed = groupRepo.findById(groupId).orElseThrow();
        refreshSummary(refreshed);
        groupRepo.save(refreshed);

        return toDto(refreshed);
    }



    @Transactional
    public List<CandidateSummaryDto> findCandidatesForGroup(
            Long groupId,
            String q,
            String email,
            String phone,
            String gender,
            String companionPreference,
            String whenLabel,
            String destination,
            Boolean ignoreTravelDates
    ) {
        TravelGroup group = groupRepo.findById(groupId).orElseThrow();

        StringBuilder jpql = new StringBuilder("""
        SELECT r
          FROM TravelRequest r
         WHERE r.status = app.coincidir.api.domain.RequestStatus.NEW
           AND r.group IS NULL
    """);

        Map<String, Object> params = new java.util.HashMap<>();

        boolean ignoreDates = Boolean.TRUE.equals(ignoreTravelDates);

        // Destino / fecha “efectivos”: si no vienen en los filtros, usamos los del grupo
        String effectiveDest = (destination != null && !destination.isBlank())
                ? destination
                : group.getDestination();

        // Fecha: por defecto usa la del grupo, salvo que se pida ignorar fechas.
        // Si ignoreDates=true, sólo filtra por whenLabel si el usuario lo envía explícitamente.
        String effectiveWhen = null;
        if (!ignoreDates) {
            effectiveWhen = (whenLabel != null && !whenLabel.isBlank())
                    ? whenLabel
                    : group.getWhenLabel();
        } else {
            effectiveWhen = (whenLabel != null && !whenLabel.isBlank()) ? whenLabel : null;
        }

        if (effectiveDest != null && !effectiveDest.isBlank()) {
            jpql.append(" AND r.destination = :dest");
            params.put("dest", effectiveDest);
        }

        if (effectiveWhen != null && !effectiveWhen.isBlank()) {
            jpql.append(" AND r.whenLabel = :whenLabel");
            params.put("whenLabel", effectiveWhen);
        }

        // Resto de filtros (igual que ya tenías)
        if (gender != null && !gender.isBlank()) {
            jpql.append(" AND UPPER(r.gender) = :gender");
            params.put("gender", gender.toUpperCase());
        }

        if (companionPreference != null && !companionPreference.isBlank()) {
            jpql.append(" AND UPPER(r.companionPreference) = :compPref");
            params.put("compPref", companionPreference.toUpperCase());
        }

        if (email != null && !email.isBlank()) {
            jpql.append(" AND LOWER(r.email) LIKE :email");
            params.put("email", "%" + email.toLowerCase() + "%");
        }

        if (phone != null && !phone.isBlank()) {
            jpql.append(" AND r.phone LIKE :phone");
            params.put("phone", "%" + phone + "%");
        }

        if (q != null && !q.isBlank()) {
            jpql.append("""
            AND (
                  LOWER(r.name)  LIKE :q
               OR LOWER(r.email) LIKE :q
               OR r.phone       LIKE :q
            )
        """);
            params.put("q", "%" + q.toLowerCase() + "%");
        }

        jpql.append(" ORDER BY r.createdAt ASC");

        var query = em.createQuery(jpql.toString(), TravelRequest.class);
        params.forEach(query::setParameter);

        return query.getResultList()
                .stream()
                .map(r -> CandidateSummaryDto.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .email(r.getEmail())
                        .phone(r.getPhone())
                        .gender(r.getGender())
                        .companionPreference(r.getCompanionPreference())
                        .whenLabel(r.getWhenLabel())
                        .destination(r.getDestination())
                        .build())
                .toList();
    }




    private static String paxRange(List<TravelRequest> ms) {
        Integer pmin = ms.stream().map(TravelRequest::getPaxMin).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        Integer pmax = ms.stream().map(TravelRequest::getPaxMax).filter(Objects::nonNull).max(Integer::compareTo).orElse(null);
        return (pmin == null && pmax == null) ? null :
                ("Min: " + (pmin == null ? "-" : pmin) + " · Max: " + (pmax == null ? "-" : pmax));
    }

    private static String majority(List<TravelRequest> ms, Function<TravelRequest, String> f) {
        return ms.stream().map(f).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
    }

    private static String majorityBool(List<TravelRequest> ms, Function<TravelRequest, Boolean> f) {
        long total = ms.stream().map(f).filter(Objects::nonNull).count();
        if (total == 0) return null;
        long yes = ms.stream().map(f).filter(Boolean.TRUE::equals).count();
        return yes * 2 >= total ? "Sí" : "No";
    }

    private static String ageRange(List<TravelRequest> ms) {
        Integer min = ms.stream().map(TravelRequest::getAgeMin).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        Integer max = ms.stream().map(TravelRequest::getAgeMax).filter(Objects::nonNull).max(Integer::compareTo).orElse(null);
        return (min == null || max == null) ? null : (min + "-" + max);
    }

    private static Boolean smokeFreeSafe(TravelRequest r) {
        // soporta null sin NPE
        return r.getSmokeFree() == null ? null : r.getSmokeFree();
    }

    private static void putIfPresent(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String safe(Object o) { return o == null ? null : String.valueOf(o); }
}