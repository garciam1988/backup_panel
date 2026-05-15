package app.coincidir.api.service;

import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.RequestStatus;
import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.MemberPaymentRecord;
import app.coincidir.api.repository.*;
import app.coincidir.api.web.user.dto.UserTripDto;
import app.coincidir.api.web.user.dto.UserTripsMeDto;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserTripsService {

    private static final ZoneId AR_TZ = ZoneId.of("America/Argentina/Buenos_Aires");

    private final TravelRequestRepository requestRepo;
    private final TravelGroupRepository groupRepo;

    private final MemberAccommodationServiceRepository memberAccommodationRepo;
    private final MemberAirServiceRepository memberAirRepo;
    private final MemberDestinationTransferServiceRepository memberDestinationTransferRepo;
    private final MemberFerryServiceRepository memberFerryRepo;
    private final MemberTransferServiceRepository memberTransferRepo;

    private final MemberOptionalExcursionServiceRepository memberOptionalExcursionRepo;
    private final MemberOptionalLuggageServiceRepository memberOptionalLuggageRepo;
    private final MemberOptionalTravelAssistanceServiceRepository memberOptionalTravelAssistanceRepo;
    private final MemberOptionalServiceMenuItemRepository memberOptionalMenuRepo;

    private final MemberPaymentPlanRepository paymentPlanRepo;
    private final MemberPaymentRecordRepository paymentRecordRepo;
    private final FinancialMovementConciliationRepository conciliationRepo;

    public UserTripsMeDto listTripsForUser(String email) {
        if (email == null || email.isBlank()) {
            return UserTripsMeDto.builder().trips(List.of()).build();
        }

        List<TravelRequest> requests = requestRepo.findByEmailIgnoreCase(email.trim());
        if (requests == null) requests = List.of();

        List<UserTripDto> trips = requests.stream()
                .filter(Objects::nonNull)
                .filter(r -> r.getStatus() != RequestStatus.CANCELLED) // no mostramos canceladas antiguas
                .sorted(Comparator.comparing((TravelRequest r) -> r.getCreatedAt() != null ? r.getCreatedAt() : java.time.LocalDateTime.MIN).reversed()
                        .thenComparing(TravelRequest::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toTripDto)
                .toList();

        return UserTripsMeDto.builder().trips(trips).build();
    }

    @Transactional
    public void cancelRequest(String email, Long requestId) {
        if (email == null || email.isBlank()) throw new BadRequestException("Usuario no autenticado.");
        if (requestId == null) throw new BadRequestException("requestId requerido.");

        TravelRequest req = requestRepo.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Solicitud no encontrada."));

        // ownership
        String reqEmail = req.getEmail() == null ? "" : req.getEmail().trim();
        if (!reqEmail.equalsIgnoreCase(email.trim())) {
            // no filtramos por 404 para que sea más claro en debug
            throw new BadRequestException("No tenés permisos para anular esta solicitud.");
        }

        TravelGroup g = req.getGroup();
        if (g != null) {
            // no permitir anular un viaje ya finalizado
            GroupStatus st = g.getStatus();
            LocalDate today = LocalDate.now(AR_TZ);
            if (st == GroupStatus.FINALIZED) {
                throw new BadRequestException("No se puede anular un viaje finalizado.");
            }
            if (st == GroupStatus.CLOSED && g.getTravelEndDate() != null && g.getTravelEndDate().isBefore(today)) {
                throw new BadRequestException("No se puede anular un viaje finalizado.");
            }

            // si hay pagos registrados, no permitir borrado por panel usuario
            List<MemberPaymentRecord> existing = paymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(g.getId(), req.getId());
            if (existing != null && !existing.isEmpty()) {
                throw new BadRequestException("No se puede anular esta solicitud porque tiene pagos registrados. Contactá a un administrador.");
            }
        }

        Long memberId = req.getId();

        // 1) borrar servicios por miembro (evita FK)
        safeDeleteMemberServices(memberId);

        // 2) borrar pagos/planes (si existieran)
        safeDeleteMemberPayments(memberId);

        // 3) borrar request
        Long groupId = (g != null ? g.getId() : null);
        requestRepo.delete(req);

        // 4) si el grupo quedó sin miembros, lo cancelamos para que no lo tenga en cuenta el motor
        if (groupId != null) {
            long left = requestRepo.countByGroupId(groupId);
            if (left <= 0) {
                TravelGroup grp = groupRepo.findById(groupId).orElse(null);
                if (grp != null && grp.getStatus() != GroupStatus.FINALIZED) {
                    grp.setStatus(GroupStatus.CANCELLED);
                    groupRepo.save(grp);
                }
            }
        }
    }

    private void safeDeleteMemberServices(Long memberId) {
        // member_* services
        memberAccommodationRepo.deleteByMemberId(memberId);
        memberAirRepo.deleteByMemberId(memberId);
        memberDestinationTransferRepo.deleteByMemberId(memberId);
        memberFerryRepo.deleteByMemberId(memberId);
        memberTransferRepo.deleteByMemberId(memberId);

        // optional services
        memberOptionalExcursionRepo.deleteByMemberId(memberId);
        memberOptionalLuggageRepo.deleteByMemberId(memberId);
        memberOptionalTravelAssistanceRepo.deleteByMemberId(memberId);
        memberOptionalMenuRepo.deleteByMemberId(memberId);
    }

    private void safeDeleteMemberPayments(Long memberId) {
        List<MemberPaymentPlan> plans = paymentPlanRepo.findAllByMemberId(memberId);
        if (plans == null || plans.isEmpty()) return;

        // borrar records + conciliaciones por record id
        List<Long> recordIds = new ArrayList<>();

        for (MemberPaymentPlan p : plans) {
            if (p == null || p.getId() == null) continue;

            List<MemberPaymentRecord> records = paymentRecordRepo.findAllByPlanIdOrderByPaymentDateDescIdDesc(p.getId());
            if (records != null) {
                for (MemberPaymentRecord r : records) {
                    if (r != null && r.getId() != null) recordIds.add(r.getId());
                }
                paymentRecordRepo.deleteAll(records);
            }
        }

        if (!recordIds.isEmpty()) {
            List<FinancialMovementConciliation> conc = conciliationRepo.findAllByMovementTypeAndMovementIdIn(FinancialMovementType.MEMBER_PAYMENT_RECORD, recordIds);
            if (conc != null && !conc.isEmpty()) {
                conciliationRepo.deleteAll(conc);
            }
        }

        paymentPlanRepo.deleteAll(plans);
    }

    private UserTripDto toTripDto(TravelRequest r) {
        TravelGroup g = r.getGroup();
        return UserTripDto.builder()
                .requestId(r.getId())
                .groupId(g != null ? g.getId() : null)
                .destination(r.getDestination())
                .whenLabel(r.getWhenLabel())
                .status(g != null && g.getStatus() != null ? g.getStatus().name() : null)
                .requestStatus(r.getStatus() != null ? r.getStatus().name() : null)
                .travelStartDate(g != null && g.getTravelStartDate() != null ? g.getTravelStartDate().toString() : null)
                .travelEndDate(g != null && g.getTravelEndDate() != null ? g.getTravelEndDate().toString() : null)
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .build();
    }
}
