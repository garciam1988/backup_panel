// src/main/java/app/coincidir/api/service/GroupServiceItemsService.java
package app.coincidir.api.service;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.*;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.repository.*;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import app.coincidir.api.domain.AccommodationContractType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
@Service
@RequiredArgsConstructor
public class GroupServiceItemsService {

    private final AuthorizationCodeService authCodeService;
    private final OperationsRoleGuardService operationsRoleGuardService;

    private final GroupServiceMenuItemRepository menuItemRepo;

    private final GroupFerryServiceRepository ferryRepo;
    private final GroupTransferServiceRepository transferRepo;
    private final GroupDestinationTransferServiceRepository destinationTransferRepo;
    private final GroupAccommodationServiceRepository accommodationRepo;
    private final GroupAccommodationRoomRepository accommodationRoomRepo;
    private final JdbcTemplate jdbc;
    private final GroupAirServiceRepository airRepo;
    private final ExpenseRepository expenseRepo;

    // NEW: para replicar a miembros
    private final TravelRequestRepository travelRequestRepo;

    private final TravelRequestAirServiceRepository requestAirRepo;

    private final MemberFerryServiceRepository memberFerryRepo;
    private final MemberTransferServiceRepository memberTransferRepo;
    private final MemberDestinationTransferServiceRepository memberDestinationTransferRepo;
    private final MemberAccommodationServiceRepository memberAccommodationRepo;
    private final MemberAirServiceRepository memberAirRepo;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private GroupServiceMenuItem getMenuItemOrThrow(Long groupId, Long menuItemId) {
        return menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new NotFoundException("Service menu item no encontrado: " + menuItemId + " (grupo " + groupId + ")"));
    }

    private void assertServiceCode(GroupServiceMenuItem menuItem, ServiceCode expected) {
        ServiceCode actual = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        if (actual != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El item " + menuItem.getId() + " no es del tipo esperado. Esperado: " + expected + ", actual: " + actual);
        }
    }

    private LocalDate parseNullableDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value, DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha inválida: " + value);
        }
    }

    private LocalTime parseNullableTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value, TIME_FMT);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hora inválida: " + value);
        }
    }

    private String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private TransferTripType normalizeTransferTripType(String tripTypeValue, LocalDate returnDate, LocalTime returnTime) {
        String normalized = nullIfBlank(tripTypeValue);
        if (normalized == null) {
            return (returnDate != null || returnTime != null) ? TransferTripType.ROUND_TRIP : TransferTripType.ONE_WAY;
        }

        try {
            return TransferTripType.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tripType inválido. Use ONE_WAY o ROUND_TRIP");
        }
    }

    /**
     * Cotización:
     * - quotedAt debe reflejar el momento real en el que se modificó el valor cotizado.
     * - Si el front re-envía el mismo quotedValue (readOnly) NO debe pisar quotedAt.
     */
    private void applyQuoteIfChanged(
            BigDecimal currentValue,
            BigDecimal incomingValue,
            java.util.function.Consumer<BigDecimal> setValue,
            java.util.function.Consumer<Instant> setAt
    ) {
        // El front puede enviar updates parciales (sin re-enviar cotización).
        // En ese caso incomingValue llega null y NO debe interpretarse como "limpiar".
        if (incomingValue == null) return;
        if (currentValue == null || incomingValue.compareTo(currentValue) != 0) {
            setValue.accept(incomingValue);
            setAt.accept(Instant.now());
        }
    }

    /**
     * Costo real:
     * - totalCostUpdatedAt debe actualizarse automáticamente al momento de modificar el totalCost.
     * - Si el front re-envía el mismo totalCost NO debe pisar la fecha.
     */
    private void applyTotalCostIfChanged(
            BigDecimal currentValue,
            BigDecimal incomingValue,
            java.util.function.Consumer<BigDecimal> setValue,
            java.util.function.Consumer<Instant> setAt
    ) {
        // El front puede enviar updates parciales (sin re-enviar totalCost).
        // En ese caso incomingValue llega null y NO debe interpretarse como "limpiar".
        if (incomingValue == null) return;
        if (currentValue == null || incomingValue.compareTo(currentValue) != 0) {
            setValue.accept(incomingValue);
            setAt.accept(Instant.now());
        }
    }


    private void refreshAutoOperationStatusIfApplicable(Long groupId, GroupServiceMenuItem menuItem, ServiceCode sc) {
        if (groupId == null || menuItem == null || sc == null) return;
        if (sc != ServiceCode.FERRY) return;

        BigDecimal totalCost = null;
        try {
            totalCost = ferryRepo.findByMenuItemId(menuItem.getId())
                    .map(GroupFerryService::getTotalCost)
                    .orElse(null);
        } catch (Exception ignored) {
        }

        OperationStatusCode desired = OperationStatusCode.PENDIENTE;
        if (totalCost != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal paid = sumPaidFromExpenses(groupId, menuItem.getId(), sc);
            BigDecimal remaining = totalCost.subtract(paid != null ? paid : BigDecimal.ZERO);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                desired = OperationStatusCode.EMITIDO;
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

    private BigDecimal sumPaidFromExpenses(Long groupId, Long menuItemId, ServiceCode sc) {
        java.util.LinkedHashMap<Long, Expense> uniq = new java.util.LinkedHashMap<>();
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
                .filter(java.util.Objects::nonNull)
                .map(Expense::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireAuthorizationForTotalCostAboveQuoteIfNeeded(
            BigDecimal quotedValue,
            BigDecimal currentTotalCost,
            BigDecimal incomingTotalCost
    ) {
        if (quotedValue == null || incomingTotalCost == null) return;
        // Solo pedir autorización cuando se está cambiando el totalCost
        if (currentTotalCost != null && incomingTotalCost.compareTo(currentTotalCost) == 0) return;

        BigDecimal threshold = quotedValue.multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP);
        if (incomingTotalCost.compareTo(threshold) > 0) {
            // Regla: si el total del costo supera el valor cotizado en más de un 2%, requiere autorización ADMIN
            authCodeService.requireAuthorizationIfNotAdmin("ADMIN");
        }
    }


    // ============================================================
    // NEW: Replicación automática (grupo -> miembros) con override
    // ============================================================

    private void syncFerryToMembers(Long groupId, GroupServiceMenuItem menuItem, GroupFerryService groupEntity) {
        List<TravelRequest> members = travelRequestRepo.findByGroupId(groupId);

        for (TravelRequest m : members) {
            MemberFerryService ms = memberFerryRepo
                    .findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                    .orElseGet(() -> {
                        MemberFerryService x = new MemberFerryService();
                        x.setMenuItem(menuItem);
                        x.setMember(m);
                        x.setOverridden(false);
                        return x;
                    });
            if (ms.isOverridden()) {
                continue;
            }

            ms.setQuotedValue(groupEntity.getQuotedValue());
            ms.setQuotedAt(groupEntity.getQuotedAt());

            ms.setTripType(groupEntity.getTripType());
            ms.setOriginPort(groupEntity.getOriginPort());
            ms.setDestinationPort(groupEntity.getDestinationPort());

            ms.setDepartureDate(groupEntity.getDepartureDate());
            ms.setDepartureTime(groupEntity.getDepartureTime());
            ms.setDepartureArrivalTime(groupEntity.getDepartureArrivalTime());

            ms.setReturnDate(groupEntity.getReturnDate());
            ms.setReturnTime(groupEntity.getReturnTime());
            ms.setReturnArrivalTime(groupEntity.getReturnArrivalTime());

            ms.setFerryCompany(groupEntity.getFerryCompany());
            ms.setProvider(groupEntity.getProvider());
            ms.setReservationCode(groupEntity.getReservationCode());
            ms.setNotes(groupEntity.getNotes());

            ms.setBusOrigin(groupEntity.getBusOrigin());
            ms.setBusDestination(groupEntity.getBusDestination());
            ms.setBusDepartureTime(groupEntity.getBusDepartureTime());
            ms.setBusArrivalTime(groupEntity.getBusArrivalTime());
            ms.setReturnBusDepartureTime(groupEntity.getReturnBusDepartureTime());
            ms.setReturnBusArrivalTime(groupEntity.getReturnBusArrivalTime());

            memberFerryRepo.save(ms);
        }
    }

    private void syncTransfersToMembers(Long groupId, GroupServiceMenuItem menuItem, GroupTransferService groupEntity) {
        List<TravelRequest> members = travelRequestRepo.findByGroupId(groupId);

        for (TravelRequest m : members) {
            MemberTransferService ms = memberTransferRepo
                    .findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                    .orElseGet(() -> {
                        MemberTransferService x = new MemberTransferService();
                        x.setMenuItem(menuItem);
                        x.setMember(m);
                        x.setOverridden(false);
                        return x;
                    });
            if (ms.isOverridden()) {
                continue;
            }

            ms.setQuotedValue(groupEntity.getQuotedValue());
            ms.setQuotedAt(groupEntity.getQuotedAt());

            ms.setTripType(groupEntity.getTripType());
            ms.setPickupPlace(groupEntity.getPickupPlace());
            ms.setPickupPointName(groupEntity.getPickupPointName());
            ms.setDestinationPlace(groupEntity.getDestinationPlace());
            ms.setDestinationPointName(groupEntity.getDestinationPointName());

            ms.setDepartureDate(groupEntity.getDepartureDate());
            ms.setDepartureTime(groupEntity.getDepartureTime());
            ms.setDepartureArrivalTime(groupEntity.getDepartureArrivalTime());

            ms.setReturnDate(groupEntity.getReturnDate());
            ms.setReturnTime(groupEntity.getReturnTime());
            ms.setReturnArrivalTime(groupEntity.getReturnArrivalTime());

            ms.setNotes(groupEntity.getNotes());
            ms.setProvider(groupEntity.getProvider());
            ms.setReservationCode(groupEntity.getReservationCode());

            memberTransferRepo.save(ms);
        }
    }

    private void syncDestinationTransfersToMembers(Long groupId, GroupServiceMenuItem menuItem, GroupDestinationTransferService groupEntity) {
        List<TravelRequest> members = travelRequestRepo.findByGroupId(groupId);

        for (TravelRequest m : members) {
            MemberDestinationTransferService ms = memberDestinationTransferRepo
                    .findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                    .orElseGet(() -> {
                        MemberDestinationTransferService x = new MemberDestinationTransferService();
                        x.setMenuItem(menuItem);
                        x.setMember(m);
                        x.setOverridden(false);
                        return x;
                    });
            if (ms.isOverridden()) {
                continue;
            }

            ms.setQuotedValue(groupEntity.getQuotedValue());
            ms.setQuotedAt(groupEntity.getQuotedAt());

            ms.setTripType(groupEntity.getTripType());
            ms.setPickupPlace(groupEntity.getPickupPlace());
            ms.setPickupPointName(groupEntity.getPickupPointName());
            ms.setDestinationPlace(groupEntity.getDestinationPlace());
            ms.setDestinationPointName(groupEntity.getDestinationPointName());

            ms.setDepartureDate(groupEntity.getDepartureDate());
            ms.setDepartureTime(groupEntity.getDepartureTime());
            ms.setDepartureArrivalTime(groupEntity.getDepartureArrivalTime());

            ms.setReturnDate(groupEntity.getReturnDate());
            ms.setReturnTime(groupEntity.getReturnTime());
            ms.setReturnArrivalTime(groupEntity.getReturnArrivalTime());

            ms.setCountry(groupEntity.getCountry());
            ms.setCity(groupEntity.getCity());

            ms.setNotes(groupEntity.getNotes());
            ms.setProvider(groupEntity.getProvider());
            ms.setReservationCode(groupEntity.getReservationCode());

            memberDestinationTransferRepo.save(ms);
        }
    }

    private void syncAccommodationToMembers(Long groupId, GroupServiceMenuItem menuItem, GroupAccommodationService groupEntity) {
        List<TravelRequest> members = travelRequestRepo.findByGroupId(groupId);

        for (TravelRequest m : members) {
            MemberAccommodationService ms = memberAccommodationRepo
                    .findByMenuItemIdAndMemberId(menuItem.getId(), m.getId())
                    .orElseGet(() -> {
                        MemberAccommodationService x = new MemberAccommodationService();
                        x.setMenuItem(menuItem);
                        x.setMember(m);
                        x.setOverridden(false);
                        return x;
                    });
            if (ms.isOverridden()) {
                continue;
            }

            ms.setQuotedValue(groupEntity.getQuotedValue());
            ms.setQuotedAt(groupEntity.getQuotedAt());

            ms.setName(groupEntity.getName());
            ms.setCheckInDate(groupEntity.getCheckInDate());
            ms.setCheckInTime(groupEntity.getCheckInTime());
            ms.setCheckOutDate(groupEntity.getCheckOutDate());
            ms.setCheckOutTime(groupEntity.getCheckOutTime());
            ms.setRegimen(groupEntity.getRegimen());

            ms.setCountry(groupEntity.getCountry());
            ms.setCity(groupEntity.getCity());

            ms.setContractType(groupEntity.getContractType() != null ? groupEntity.getContractType() : AccommodationContractType.DIRECTA);
            ms.setThirdPartyName(groupEntity.getThirdPartyName());
            ms.setProviderId(groupEntity.getProviderId());
            ms.setAccommodationId(groupEntity.getAccommodationId());
            ms.setReservationCode(groupEntity.getReservationCode());

            memberAccommodationRepo.save(ms);
        }
    }

    private void syncAirToMembers(Long groupId, GroupServiceMenuItem menuItem, GroupAirService groupEntity) {
        List<TravelRequest> members = travelRequestRepo.findByGroupId(groupId);

        for (TravelRequest m : members) {
            java.util.Optional<MemberAirService> existingOpt = memberAirRepo.findByMenuItemIdAndMemberId(menuItem.getId(), m.getId());
            if (existingOpt.isPresent()) {
                MemberAirService existing = existingOpt.get();
                if (existing.isOverridden()) {
                    continue;
                }

                existing.setQuotedValue(groupEntity.getQuotedValue());
                existing.setQuotedAt(groupEntity.getQuotedAt());

                existing.setTripType(groupEntity.getTripType());
                existing.setOrigin(groupEntity.getOrigin());
                existing.setDestination(groupEntity.getDestination());
                existing.setAirline(groupEntity.getAirline());

                existing.setDepartureDate(groupEntity.getDepartureDate());
                existing.setDepartureTime(groupEntity.getDepartureTime());
                existing.setDepartureArrivalTime(groupEntity.getDepartureArrivalTime());

                existing.setReturnDate(groupEntity.getReturnDate());
                existing.setReturnDepartureTime(groupEntity.getReturnDepartureTime());
                existing.setReturnArrivalTime(groupEntity.getReturnArrivalTime());

                existing.setBaggageAllowance(groupEntity.getBaggageAllowance());
                existing.setReservationCode(groupEntity.getReservationCode());
                memberAirRepo.save(existing);
                continue;
            }

            // Si el pasajero tiene precarga, crear override para que no se pierda al sincronizar
            requestAirRepo.findByRequest_Id(m.getId()).ifPresent(prefill -> {
                MemberAirService x = new MemberAirService();
                x.setMenuItem(menuItem);
                x.setMember(m);
                x.setOverridden(true);

                x.setQuotedValue(prefill.getQuotedValue());
                x.setQuotedAt(prefill.getQuotedAt());

                x.setTripType(prefill.getTripType());
                x.setOrigin(prefill.getOrigin());
                x.setDestination(prefill.getDestination());
                x.setAirline(prefill.getAirline());

                x.setDepartureDate(prefill.getDepartureDate());
                x.setDepartureTime(prefill.getDepartureTime());
                x.setDepartureArrivalTime(prefill.getDepartureArrivalTime());

                x.setReturnDate(prefill.getReturnDate());
                x.setReturnDepartureTime(prefill.getReturnDepartureTime());
                x.setReturnArrivalTime(prefill.getReturnArrivalTime());

                x.setBaggageAllowance(prefill.getBaggageAllowance());
                x.setReservationCode(prefill.getReservationCode());
                memberAirRepo.save(x);
            });

            // Si quedó creado por precarga, no aplicar valores del grupo
            if (memberAirRepo.findByMenuItemIdAndMemberId(menuItem.getId(), m.getId()).isPresent()) {
                continue;
            }

            // Caso normal: crear para el miembro copiando desde servicio de grupo
            MemberAirService ms = new MemberAirService();
            ms.setMenuItem(menuItem);
            ms.setMember(m);
            ms.setOverridden(false);

            ms.setQuotedValue(groupEntity.getQuotedValue());
            ms.setQuotedAt(groupEntity.getQuotedAt());

            ms.setTripType(groupEntity.getTripType());
            ms.setOrigin(groupEntity.getOrigin());
            ms.setDestination(groupEntity.getDestination());
            ms.setAirline(groupEntity.getAirline());

            ms.setDepartureDate(groupEntity.getDepartureDate());
            ms.setDepartureTime(groupEntity.getDepartureTime());
            ms.setDepartureArrivalTime(groupEntity.getDepartureArrivalTime());

            ms.setReturnDate(groupEntity.getReturnDate());
            ms.setReturnDepartureTime(groupEntity.getReturnDepartureTime());
            ms.setReturnArrivalTime(groupEntity.getReturnArrivalTime());

            ms.setBaggageAllowance(groupEntity.getBaggageAllowance());
            ms.setReservationCode(groupEntity.getReservationCode());

            memberAirRepo.save(ms);
        }
    }

    // -------------------
    // FERRY
    // -------------------

    @Transactional(readOnly = true)
    public GroupFerryServiceDto getFerry(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.FERRY);
        return ferryRepo.findByMenuItemId(menuItemId)
                .map(GroupFerryServiceDto::fromEntity)
                .orElse(null);
    }

    @Transactional
    public GroupFerryServiceDto upsertFerry(Long groupId, Long menuItemId, UpsertGroupFerryRequest request) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.FERRY);

        GroupFerryService entity = ferryRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    GroupFerryService g = new GroupFerryService();
                    g.setMenuItem(menuItem);
                    return g;
                });

        if (request.getTripType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El tipo de viaje (tripType) es obligatorio");
        }

        entity.setTripType(request.getTripType());
        entity.setOriginPort(nullIfBlank(request.getOriginPort()));
        entity.setDestinationPort(nullIfBlank(request.getDestinationPort()));

        entity.setDepartureDate(parseNullableDate(request.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(request.getDepartureTime()));
        entity.setDepartureArrivalTime(parseNullableTime(request.getDepartureArrivalTime()));

        entity.setReturnDate(parseNullableDate(request.getReturnDate()));
        entity.setReturnTime(parseNullableTime(request.getReturnTime()));
        entity.setReturnArrivalTime(parseNullableTime(request.getReturnArrivalTime()));

        if (entity.getTripType() == FerryTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
            entity.setReturnArrivalTime(null);
        }

        entity.setFerryCompany(nullIfBlank(request.getFerryCompany()));
        entity.setProvider(nullIfBlank(request.getProvider()));

        // Bus fields
        entity.setBusOrigin(nullIfBlank(request.getBusOrigin()));
        entity.setBusDestination(nullIfBlank(request.getBusDestination()));
        entity.setBusDepartureTime(parseNullableTime(request.getBusDepartureTime()));
        entity.setBusArrivalTime(parseNullableTime(request.getBusArrivalTime()));
        if (entity.getTripType() == FerryTripType.ONE_WAY) {
            entity.setReturnBusDepartureTime(null);
            entity.setReturnBusArrivalTime(null);
        } else {
            entity.setReturnBusDepartureTime(parseNullableTime(request.getReturnBusDepartureTime()));
            entity.setReturnBusArrivalTime(parseNullableTime(request.getReturnBusArrivalTime()));
        }

        String rc = nullIfBlank(request.getReservationCode());
        // Si el front no lo re-envía pero ya estaba guardado, conservar el existente.
        if (rc == null) {
            rc = nullIfBlank(entity.getReservationCode());
        }
        // Puede completarse en otra etapa; NO debe bloquear el guardado.
        entity.setReservationCode(rc);
        entity.setNotes(nullIfBlank(request.getNotes()));

        // Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
        authCodeService.requireAuthorizationForQuoteChangeIfNeeded(entity.getQuotedValue(), request.getQuotedValue());
        applyQuoteIfChanged(entity.getQuotedValue(), request.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        requireAuthorizationForTotalCostAboveQuoteIfNeeded(entity.getQuotedValue(), entity.getTotalCost(), request.getTotalCost());
        applyTotalCostIfChanged(entity.getTotalCost(), request.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);
        GroupFerryService saved = ferryRepo.save(entity);
        syncFerryToMembers(groupId, menuItem, saved);
        refreshAutoOperationStatusIfApplicable(groupId, menuItem, ServiceCode.FERRY);
        return GroupFerryServiceDto.fromEntity(saved);
    }

    // -------------------
    // TRASLADOS BA
    // -------------------

    @Transactional(readOnly = true)
    public GroupTransferServiceDto getTransfers(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS);
        return transferRepo.findByMenuItemId(menuItemId)
                .map(GroupTransferServiceDto::fromEntity)
                .orElse(null);
    }

    @Transactional
    public GroupTransferServiceDto upsertTransfers(Long groupId, Long menuItemId, UpsertGroupTransferRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS);

        GroupTransferService entity = transferRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    GroupTransferService gs = new GroupTransferService();
                    gs.setMenuItem(menuItem);
                    return gs;
                });

        entity.setPickupPlace(nullIfBlank(req.getPickupAddress()));
        entity.setPickupPointName(nullIfBlank(req.getPickupPointName()));
        entity.setDestinationPlace(nullIfBlank(req.getDropoffAddress()));
        entity.setDestinationPointName(nullIfBlank(req.getDropoffPointName()));

        TransferTripType tripType = normalizeTransferTripType(req.getTripType(), parseNullableDate(req.getReturnDate()), parseNullableTime(req.getReturnTime()));
        entity.setTripType(tripType);

        entity.setDepartureDate(parseNullableDate(req.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(req.getDepartureTime()));
        entity.setReturnDate(parseNullableDate(req.getReturnDate()));
        entity.setReturnTime(parseNullableTime(req.getReturnTime()));

        if (tripType == TransferTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
        }

        entity.setNotes(nullIfBlank(req.getNotes()));
        entity.setProvider(nullIfBlank(req.getProvider()));
        String rc = nullIfBlank(req.getReservationCode());
        // Si el front no lo re-envía pero ya estaba guardado, conservar el existente.
        if (rc == null) {
            rc = nullIfBlank(entity.getReservationCode());
        }
        // Puede completarse en otra etapa; NO debe bloquear el guardado.
        entity.setReservationCode(rc);
        entity.setDepartureArrivalTime(parseNullableTime(req.getDepartureArrivalTime()));
        entity.setReturnArrivalTime(parseNullableTime(req.getReturnArrivalTime()));

        if (entity.getReturnDate() == null || entity.getReturnTime() == null) {
            entity.setReturnArrivalTime(null);
        }

        // Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
        authCodeService.requireAuthorizationForQuoteChangeIfNeeded(entity.getQuotedValue(), req.getQuotedValue());
        applyQuoteIfChanged(entity.getQuotedValue(), req.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        requireAuthorizationForTotalCostAboveQuoteIfNeeded(entity.getQuotedValue(), entity.getTotalCost(), req.getTotalCost());
        applyTotalCostIfChanged(entity.getTotalCost(), req.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);

        GroupTransferService saved = transferRepo.save(entity);
        syncTransfersToMembers(groupId, menuItem, saved);
        return GroupTransferServiceDto.fromEntity(saved);
    }

    // -------------------
    // TRASLADOS DESTINO
    // -------------------

    @Transactional(readOnly = true)
    public GroupDestinationTransferServiceDto getDestinationTransfers(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS_DESTINO);
        return destinationTransferRepo.findByMenuItemId(menuItemId)
                .map(GroupDestinationTransferServiceDto::fromEntity)
                .orElse(null);
    }

    @Transactional
    public GroupDestinationTransferServiceDto upsertDestinationTransfers(Long groupId, Long menuItemId, UpsertGroupTransferRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS_DESTINO);

        GroupDestinationTransferService entity = destinationTransferRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    GroupDestinationTransferService gs = new GroupDestinationTransferService();
                    gs.setMenuItem(menuItem);
                    return gs;
                });

        entity.setPickupPlace(nullIfBlank(req.getPickupAddress()));
        entity.setPickupPointName(nullIfBlank(req.getPickupPointName()));
        entity.setDestinationPlace(nullIfBlank(req.getDropoffAddress()));
        entity.setDestinationPointName(nullIfBlank(req.getDropoffPointName()));

        TransferTripType tripType = normalizeTransferTripType(req.getTripType(), parseNullableDate(req.getReturnDate()), parseNullableTime(req.getReturnTime()));
        entity.setTripType(tripType);

        entity.setDepartureDate(parseNullableDate(req.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(req.getDepartureTime()));
        entity.setReturnDate(parseNullableDate(req.getReturnDate()));
        entity.setReturnTime(parseNullableTime(req.getReturnTime()));

        if (tripType == TransferTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
        }

        String country = nullIfBlank(req.getCountry());
        entity.setCountry(country != null ? country : "Argentina");
        entity.setCity(nullIfBlank(req.getCity()));

        entity.setDepartureArrivalTime(parseNullableTime(req.getDepartureArrivalTime()));
        entity.setReturnArrivalTime(parseNullableTime(req.getReturnArrivalTime()));
        if (entity.getReturnDate() == null || entity.getReturnTime() == null) {
            entity.setReturnArrivalTime(null);
        }

        entity.setNotes(nullIfBlank(req.getNotes()));
        entity.setProvider(nullIfBlank(req.getProvider()));
        String rc = nullIfBlank(req.getReservationCode());
        // Si el front no lo re-envía pero ya estaba guardado, conservar el existente.
        if (rc == null) {
            rc = nullIfBlank(entity.getReservationCode());
        }
        // Puede completarse en otra etapa; NO debe bloquear el guardado.
        entity.setReservationCode(rc);

        // Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
        authCodeService.requireAuthorizationForQuoteChangeIfNeeded(entity.getQuotedValue(), req.getQuotedValue());

        applyQuoteIfChanged(entity.getQuotedValue(), req.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        requireAuthorizationForTotalCostAboveQuoteIfNeeded(entity.getQuotedValue(), entity.getTotalCost(), req.getTotalCost());
        applyTotalCostIfChanged(entity.getTotalCost(), req.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);

        GroupDestinationTransferService saved = destinationTransferRepo.save(entity);
        syncDestinationTransfersToMembers(groupId, menuItem, saved);
        return GroupDestinationTransferServiceDto.fromEntity(saved);
    }

    // -------------------
    // ALOJAMIENTOS
    // -------------------

    @Transactional(readOnly = true)
    public GroupAccommodationServiceDto getAccommodation(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.ALOJAMIENTOS);
        return accommodationRepo.findByMenuItemId(menuItemId)
                .map(e -> {
                    // Compatibilidad Admin Panel: si providerId o accommodationId son null,
                    // intentar resolverlos por nombre para que Operations Panel los muestre.
                    if (e.getProviderId() == null && e.getThirdPartyName() != null && !e.getThirdPartyName().isBlank()) {
                        Long resolvedProviderId = fetchAccommodationProviderIdByName(e.getThirdPartyName());
                        if (resolvedProviderId != null) {
                            e.setProviderId(resolvedProviderId);
                            accommodationRepo.save(e);
                        }
                    }
                    if (e.getAccommodationId() == null && e.getName() != null && !e.getName().isBlank()) {
                        Long resolvedAccommodationId = fetchAccommodationIdByName(e.getName());
                        if (resolvedAccommodationId != null) {
                            e.setAccommodationId(resolvedAccommodationId);
                            accommodationRepo.save(e);
                        }
                    }
                    GroupAccommodationServiceDto dto = GroupAccommodationServiceDto.fromEntity(e);
                    attachAccommodationRooms(dto, e.getId());
                    return dto;
                })
                .orElse(null);
    }

    @Transactional
    public GroupAccommodationServiceDto upsertAccommodation(Long groupId, Long menuItemId, UpsertGroupAccommodationRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.ALOJAMIENTOS);

        GroupAccommodationService entity = accommodationRepo.findByMenuItemId(menuItemId)
                .orElseGet(() -> {
                    GroupAccommodationService g = new GroupAccommodationService();
                    g.setMenuItem(menuItem);
                    return g;
                });

        if (req.getName() == null || req.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del alojamiento es obligatorio");
        }
        if (req.getCheckInDate() == null || req.getCheckOutDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-in y check-out son obligatorios");
        }
        if (req.getRegimen() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El régimen es obligatorio");
        }

        entity.setName(nullIfBlank(req.getName()));
        entity.setCheckInDate(parseNullableDate(req.getCheckInDate()));
        entity.setCheckOutDate(parseNullableDate(req.getCheckOutDate()));
        entity.setRegimen(req.getRegimen());

        String country = nullIfBlank(req.getCountry());
        entity.setCountry(country != null ? country : "Argentina");
        entity.setCity(nullIfBlank(req.getCity()));

        // Proveedor & Prestador (combos anidados)
        // Nota: si el front no re-envía estos ids en un update parcial, no debemos limpiarlos.
        // Compatibilidad Admin Panel: si no viene providerId pero sí nombre, resolverlo por nombre.
        Long providerId = (req.getProviderId() != null) ? req.getProviderId() : entity.getProviderId();
        if (providerId == null) {
            String provNameRaw = nullIfBlank(req.getProvider());
            if (provNameRaw == null) provNameRaw = nullIfBlank(req.getThirdPartyName());
            if (provNameRaw != null) {
                Long resolvedId = fetchAccommodationProviderIdByName(provNameRaw);
                if (resolvedId != null) providerId = resolvedId;
            }
        }

        Long previousAccommodationId = entity.getAccommodationId();
        Long accommodationId = (req.getAccommodationId() != null) ? req.getAccommodationId() : previousAccommodationId;
        // Compatibilidad Admin Panel: si no viene accommodationId pero sí nombre, resolverlo por nombre.
        if (accommodationId == null) {
            String accNameRaw = nullIfBlank(req.getName());
            if (accNameRaw != null) {
                Long resolvedId = fetchAccommodationIdByName(accNameRaw);
                if (resolvedId != null) accommodationId = resolvedId;
            }
        }

        boolean accommodationChanged = req.getAccommodationId() != null && !java.util.Objects.equals(req.getAccommodationId(), previousAccommodationId);
        entity.setProviderId(providerId);
        entity.setAccommodationId(accommodationId);

        LocalTime nextCheckInTime = entity.getCheckInTime();
        String requestedCheckInTime = nullIfBlank(req.getCheckInTime());
        if (requestedCheckInTime != null) {
            nextCheckInTime = parseNullableTime(requestedCheckInTime);
        } else if (accommodationChanged || nextCheckInTime == null) {
            LocalTime defaultCheckInTime = fetchAccommodationDefaultCheckTime(accommodationId, true);
            if (defaultCheckInTime != null) {
                nextCheckInTime = defaultCheckInTime;
            }
        }
        entity.setCheckInTime(nextCheckInTime);

        LocalTime nextCheckOutTime = entity.getCheckOutTime();
        String requestedCheckOutTime = nullIfBlank(req.getCheckOutTime());
        if (requestedCheckOutTime != null) {
            nextCheckOutTime = parseNullableTime(requestedCheckOutTime);
        } else if (accommodationChanged || nextCheckOutTime == null) {
            LocalTime defaultCheckOutTime = fetchAccommodationDefaultCheckTime(accommodationId, false);
            if (defaultCheckOutTime != null) {
                nextCheckOutTime = defaultCheckOutTime;
            }
        }
        entity.setCheckOutTime(nextCheckOutTime);

        boolean providerTouched = (req.getProviderId() != null)
                || (req.getAccommodationId() != null)
                || (req.getProvider() != null && !req.getProvider().isBlank())
                || (req.getThirdPartyName() != null && !req.getThirdPartyName().isBlank())
                || (req.getContractType() != null);

        if (providerTouched) {
            // Proveedor (alias de thirdPartyName)
            String provider = nullIfBlank(req.getProvider());
            if (providerId != null) {
                String providerName = fetchAccommodationProviderNameById(providerId);
                if (providerName == null || providerName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proveedor inválido");
                }
                provider = providerName;
            }
            AccommodationContractType ct = req.getContractType() != null
                    ? req.getContractType()
                    : (provider != null ? AccommodationContractType.TERCERIZADO : AccommodationContractType.DIRECTA);

            String thirdParty = provider != null ? provider : nullIfBlank(req.getThirdPartyName());
            if (ct == AccommodationContractType.TERCERIZADO && (thirdParty == null || thirdParty.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del proveedor es obligatorio");
            }

            entity.setContractType(ct);
            entity.setThirdPartyName(ct == AccommodationContractType.TERCERIZADO ? thirdParty : null);
        }

        String rc = nullIfBlank(req.getReservationCode());
        // Si el front no lo re-envía pero ya estaba guardado, conservar el existente.
        if (rc == null) {
            rc = nullIfBlank(entity.getReservationCode());
        }
        // Puede completarse en otra etapa; NO debe bloquear el guardado.
        entity.setReservationCode(rc);

        // Importe de la reserva — si viene, persiste; si no, conservar el existente.
        if (req.getReservationAmount() != null) {
            entity.setReservationAmount(req.getReservationAmount());
        }

        // Vencimiento de reserva (operaciones)
        entity.setReservationDueDate(parseNullableDate(req.getReservationDueDate()));

        // Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
        authCodeService.requireAuthorizationForQuoteChangeIfNeeded(entity.getQuotedValue(), req.getQuotedValue());
        applyQuoteIfChanged(entity.getQuotedValue(), req.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        requireAuthorizationForTotalCostAboveQuoteIfNeeded(entity.getQuotedValue(), entity.getTotalCost(), req.getTotalCost());
        applyTotalCostIfChanged(entity.getTotalCost(), req.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);

        GroupAccommodationService saved = accommodationRepo.save(entity);
        syncAccommodationToMembers(groupId, menuItem, saved);
        applyAccommodationRoomsDistribution(groupId, saved, req);

        GroupAccommodationServiceDto dto = GroupAccommodationServiceDto.fromEntity(saved);
        attachAccommodationRooms(dto, saved.getId());
        return dto;
    }

    /**
     * Habitaciones (distribución) - endpoint dedicado para el Admin Panel.
     * El front guarda el alojamiento con /accommodations y luego persiste la distribución con /accommodations/rooms.
     */
    @Transactional
    public GroupAccommodationServiceDto upsertAccommodationRooms(Long groupId, Long menuItemId, UpsertAccommodationRoomsRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.ALOJAMIENTOS);

        GroupAccommodationService accommodation = accommodationRepo.findByMenuItemId(menuItemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Primero guardá el alojamiento antes de configurar habitaciones"
                ));

        UpsertGroupAccommodationRequest tmp = new UpsertGroupAccommodationRequest();
        tmp.setRoomsCount(req != null ? req.getRoomCount() : null);

        if (req != null && req.getRooms() != null) {
            java.util.List<AccommodationRoomDistributionDto> normalized = new java.util.ArrayList<>();
            for (int i = 0; i < req.getRooms().size(); i++) {
                AccommodationRoomDistributionDto r = req.getRooms().get(i);
                Integer roomNumber = (r != null && r.getRoomNumber() != null) ? r.getRoomNumber() : (i + 1);
                Integer adults = (r != null && r.getAdults() != null) ? r.getAdults() : 0;
                Integer minors = (r != null && r.getMinors() != null) ? r.getMinors() : 0;
                String roomType = (r != null) ? r.getRoomType() : null;
                normalized.add(AccommodationRoomDistributionDto.builder()
                        .roomNumber(roomNumber)
                        .adults(adults)
                        .minors(minors)
                        .roomType(roomType)
                        .build());
            }
            tmp.setRooms(normalized);
        } else {
            tmp.setRooms(null);
        }

        applyAccommodationRoomsDistribution(groupId, accommodation, tmp);

        GroupAccommodationServiceDto dto = GroupAccommodationServiceDto.fromEntity(accommodation);
        attachAccommodationRooms(dto, accommodation.getId());
        return dto;
    }

    // -------------------
    // AEREOS
    // -------------------

    @Transactional(readOnly = true)
    public AirServiceDto getAir(Long groupId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.AEREOS);
        return airRepo.findByMenuItemId(menuItemId)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public AirServiceDto upsertAir(Long groupId, Long menuItemId, AirServiceDto request) {
        return upsertAirInternal(groupId, menuItemId, request, false);
    }

    /**
     * Uso interno para bootstrap automático (precarga de pasajeros).
     * No debe aplicar el bloqueo de OPERATIONS por pagos sin conciliar.
     */
    @Transactional
    public AirServiceDto upsertAirBootstrap(Long groupId, Long menuItemId, AirServiceDto request) {
        return upsertAirInternal(groupId, menuItemId, request, true);
    }

    private AirServiceDto upsertAirInternal(Long groupId, Long menuItemId, AirServiceDto request, boolean skipOpsGuard) {
        if (!skipOpsGuard) {
            }

        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.AEREOS);

        GroupAirService entity = airRepo.findByMenuItemId(menuItemId)
                .orElseGet(GroupAirService::new);

        // Mantener redundancia de group_id en group_air_service
        entity.setGroupId(groupId);


        
// -------------------
// Validaciones (solo cuando se envían datos de vuelo)
// -------------------
String originReq = nullIfBlank(request.getOrigin());
String destinationReq = nullIfBlank(request.getDestination());
String airlineReq = nullIfBlank(request.getAirline());
String tripTypeReq = nullIfBlank(request.getTripType());
String baggageReq = nullIfBlank(request.getBaggageAllowance());
java.time.LocalTime depTimeReq = request.getDepartureTime();
java.time.LocalTime depArrTimeReq = request.getDepartureArrivalTime();
java.time.LocalTime retDepTimeReq = request.getReturnDepartureTime();
java.time.LocalTime retArrTimeReq = request.getReturnArrivalTime();
java.time.LocalDate depDateReq = request.getDepartureDate();
java.time.LocalDate retDateReq = request.getReturnDate();

boolean hasFlightDataInRequest =
        originReq != null ||
                destinationReq != null ||
                airlineReq != null ||
                tripTypeReq != null ||
                depDateReq != null ||
                depTimeReq != null ||
                depArrTimeReq != null ||
                retDateReq != null ||
                retDepTimeReq != null ||
                retArrTimeReq != null ||
                baggageReq != null;

if (hasFlightDataInRequest) {
    // Validaciones: mostrar qué campos faltan (para que el frontend pueda informar en un modal)
    java.util.List<String> missing = new java.util.ArrayList<>();

    String originFinal = originReq != null ? originReq : nullIfBlank(entity.getOrigin());
    String destinationFinal = destinationReq != null ? destinationReq : nullIfBlank(entity.getDestination());
    String airlineFinal = airlineReq != null ? airlineReq : nullIfBlank(entity.getAirline());
    java.time.LocalDate depDateFinal = depDateReq != null ? depDateReq : entity.getDepartureDate();
    java.time.LocalTime depTimeFinal = depTimeReq != null ? depTimeReq : entity.getDepartureTime();

    if (originFinal == null) missing.add("Origen");
    if (destinationFinal == null) missing.add("Destino");
    if (airlineFinal == null) missing.add("Aerolínea");
    if (depDateFinal == null) missing.add("Fecha de salida (ida)");
    if (depTimeFinal == null) missing.add("Hora de salida (ida)");

    // Total costo:
    // - Puede cargarse en otra etapa (costos/pagos), por lo que NO debe bloquear el guardado de configuración.
    // - El estado automático seguirá en PENDIENTE hasta que exista totalCost > 0 y los pagos lo cubran.

    if (!missing.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan completar datos: " + String.join(", ", missing));
    }

    String tripType = tripTypeReq != null ? tripTypeReq : nullIfBlank(entity.getTripType());
    if (tripType == null) {
        java.time.LocalDate r = retDateReq != null ? retDateReq : entity.getReturnDate();
        tripType = (r != null) ? "ROUND_TRIP" : "ONE_WAY";
    }
    tripType = tripType.toUpperCase();
    if (!tripType.equals("ONE_WAY") && !tripType.equals("ROUND_TRIP")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tripType inválido. Use ONE_WAY o ROUND_TRIP");
    }

    if (tripType.equals("ROUND_TRIP")) {
        java.util.List<String> missingRt = new java.util.ArrayList<>();
        java.time.LocalDate retDateFinal = retDateReq != null ? retDateReq : entity.getReturnDate();
        java.time.LocalTime retDepTimeFinal = retDepTimeReq != null ? retDepTimeReq : entity.getReturnDepartureTime();
        if (retDateFinal == null) missingRt.add("Fecha de salida (regreso)");
        if (retDepTimeFinal == null) missingRt.add("Hora de salida (regreso)");
        if (!missingRt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan completar datos: " + String.join(", ", missingRt));
        }
    }

    entity.setMenuItem(menuItem);
    entity.setTripType(tripType);
    entity.setOrigin(originFinal);
    entity.setDestination(destinationFinal);
    entity.setAirline(airlineFinal);

    entity.setDepartureDate(depDateFinal);
    entity.setDepartureTime(depTimeFinal);
    entity.setDepartureArrivalTime(depArrTimeReq != null ? depArrTimeReq : entity.getDepartureArrivalTime());

    if (tripType.equals("ONE_WAY")) {
        entity.setReturnDate(null);
        entity.setReturnDepartureTime(null);
        entity.setReturnArrivalTime(null);
    } else {
        entity.setReturnDate(retDateReq != null ? retDateReq : entity.getReturnDate());
        entity.setReturnDepartureTime(retDepTimeReq != null ? retDepTimeReq : entity.getReturnDepartureTime());
        entity.setReturnArrivalTime(retArrTimeReq != null ? retArrTimeReq : entity.getReturnArrivalTime());
    }

    if (baggageReq != null) {
        entity.setBaggageAllowance(baggageReq);
    } else if (entity.getBaggageAllowance() == null || entity.getBaggageAllowance().isBlank()) {
        entity.setBaggageAllowance("6_KG");
    }
} else {
    // Actualización "liviana" (costos/pagos/obs), no exigir configuración de vuelo.
    entity.setMenuItem(menuItem);

    if (entity.getBaggageAllowance() == null || entity.getBaggageAllowance().isBlank()) {
        entity.setBaggageAllowance("6_KG");
    }
    if (entity.getTripType() == null || entity.getTripType().isBlank()) {
        entity.setTripType(entity.getReturnDate() != null ? "ROUND_TRIP" : "ONE_WAY");
    }
}

// Código de reserva: si el front no lo re-envía o viene vacío, conservar el existente
String rc = nullIfBlank(request.getReservationCode());
if (rc == null) {
    rc = nullIfBlank(entity.getReservationCode());
}
entity.setReservationCode(rc);

String notes = nullIfBlank(request.getNotes());
if (notes == null) {
    notes = nullIfBlank(entity.getNotes());
}
entity.setNotes(notes);

// Fecha vencimiento documento
if (request.getDocumentExpirationDate() != null) {
    entity.setDocumentExpirationDate(request.getDocumentExpirationDate());
}

// Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
        authCodeService.requireAuthorizationForQuoteChangeIfNeeded(entity.getQuotedValue(), request.getQuotedValue());

        applyQuoteIfChanged(entity.getQuotedValue(), request.getQuotedValue(), entity::setQuotedValue, entity::setQuotedAt);
        requireAuthorizationForTotalCostAboveQuoteIfNeeded(entity.getQuotedValue(), entity.getTotalCost(), request.getTotalCost());
        applyTotalCostIfChanged(entity.getTotalCost(), request.getTotalCost(), entity::setTotalCost, entity::setTotalCostUpdatedAt);

        GroupAirService saved = airRepo.save(entity);
        syncAirToMembers(groupId, menuItem, saved);
        refreshAutoOperationStatusIfApplicable(groupId, menuItem, ServiceCode.AEREOS);
        return toDto(saved);
    }

    private AirServiceDto toDto(GroupAirService entity) {
        AirServiceDto dto = new AirServiceDto();
        dto.setId(entity.getId());
        dto.setGroupId(entity.getMenuItem() != null && entity.getMenuItem().getGroup() != null ? entity.getMenuItem().getGroup().getId() : null);
        dto.setQuotedValue(entity.getQuotedValue());
        dto.setQuotedAt(entity.getQuotedAt() != null ? entity.getQuotedAt().toString() : null);
        dto.setTripType(entity.getTripType());
        dto.setOrigin(entity.getOrigin());
        dto.setDestination(entity.getDestination());
        dto.setAirline(entity.getAirline());
        dto.setDepartureDate(entity.getDepartureDate());
        dto.setDepartureTime(entity.getDepartureTime());
        dto.setDepartureArrivalTime(entity.getDepartureArrivalTime());
        dto.setReturnDate(entity.getReturnDate());
        dto.setReturnDepartureTime(entity.getReturnDepartureTime());
        dto.setReturnArrivalTime(entity.getReturnArrivalTime());
        dto.setBaggageAllowance(entity.getBaggageAllowance());
        dto.setReservationCode(entity.getReservationCode());
        dto.setNotes(entity.getNotes());
        dto.setDocumentExpirationDate(entity.getDocumentExpirationDate());
        dto.setTotalCost(entity.getTotalCost());
        dto.setTotalCostUpdatedAt(entity.getTotalCostUpdatedAt() != null ? entity.getTotalCostUpdatedAt().toString() : null);
        return dto;
    }


    // -------------------
    // ALOJAMIENTOS | Habitaciones (distribución)
    // -------------------

    private void attachAccommodationRooms(GroupAccommodationServiceDto dto, Long accommodationServiceId) {
        if (dto == null || accommodationServiceId == null) return;
        try {
            List<GroupAccommodationRoom> rooms = accommodationRoomRepo
                    .findByAccommodationService_IdOrderByRoomNumberAsc(accommodationServiceId);
            if (rooms == null || rooms.isEmpty()) {
                dto.setRoomsCount(null);
                dto.setRooms(null);
                return;
            }
            dto.setRoomsCount(rooms.size());
            dto.setRooms(rooms.stream()
                    .map(r -> AccommodationRoomDistributionDto.builder()
                            .roomNumber(r.getRoomNumber())
                            .adults(r.getAdults())
                            .minors(r.getMinors())
                            .roomType(r.getRoomType())
                            .build())
                    .toList());
        } catch (Exception ignored) {
        }
    }

    private void applyAccommodationRoomsDistribution(Long groupId, GroupAccommodationService accommodation, UpsertGroupAccommodationRequest req) {
        if (groupId == null || accommodation == null || accommodation.getId() == null || req == null) return;

        boolean touched = req.getRoomsCount() != null || req.getRooms() != null;
        if (!touched) return;

        // Si viene solo roomsCount sin detalle, no tocar distribución existente (evita borrados por updates parciales)
        if (req.getRooms() == null) {
            return;
        }

        List<AccommodationRoomDistributionDto> incomingRooms = req.getRooms();
        Integer roomsCount = req.getRoomsCount();

        if (incomingRooms == null || incomingRooms.isEmpty()) {
            // Interpretar lista vacía como "limpiar" distribución
            accommodationRoomRepo.deleteByAccommodationService_Id(accommodation.getId());
            return;
        }

        if (roomsCount == null) {
            roomsCount = incomingRooms.size();
        }

        if (roomsCount <= 0) {
            accommodationRoomRepo.deleteByAccommodationService_Id(accommodation.getId());
            return;
        }

        long totalPassengersLong = travelRequestRepo.countByGroupId(groupId);
        int totalPassengers = (int) Math.min(Integer.MAX_VALUE, totalPassengersLong);
        if (totalPassengers <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo determinar la cantidad de pasajeros del grupo");
        }

        if (roomsCount > totalPassengers) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cantidad de habitaciones no puede ser mayor a la cantidad de pasajeros");
        }

        if (incomingRooms.size() != roomsCount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La distribución de habitaciones no coincide con la cantidad de habitaciones seleccionada");
        }

        java.util.Set<Integer> usedNumbers = new java.util.HashSet<>();
        int totalAssigned = 0;

        java.util.List<GroupAccommodationRoom> toSave = new java.util.ArrayList<>();
        for (int i = 0; i < incomingRooms.size(); i++) {
            AccommodationRoomDistributionDto r = incomingRooms.get(i);
            Integer roomNumber = (r != null && r.getRoomNumber() != null) ? r.getRoomNumber() : (i + 1);
            Integer adults = (r != null && r.getAdults() != null) ? r.getAdults() : 0;
            Integer minors = (r != null && r.getMinors() != null) ? r.getMinors() : 0;
            String roomType = (r != null) ? r.getRoomType() : null;

            if (roomNumber < 1 || roomNumber > roomsCount) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Número de habitación inválido: " + roomNumber);
            }
            if (!usedNumbers.add(roomNumber)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Número de habitación duplicado: " + roomNumber);
            }
            if (adults < 0 || minors < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adultos/Menores no pueden ser negativos");
            }
            int occupants = adults + minors;
            if (occupants <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada habitación debe tener al menos 1 pasajero");
            }
            totalAssigned += occupants;

            GroupAccommodationRoom entity = new GroupAccommodationRoom();
            entity.setAccommodationService(accommodation);
            entity.setRoomNumber(roomNumber);
            entity.setAdults(adults);
            entity.setMinors(minors);
            entity.setRoomType(normalizeAccommodationRoomType(roomType, occupants));
            toSave.add(entity);
        }

        if (roomsCount == totalPassengers) {
            for (GroupAccommodationRoom r : toSave) {
                if ((r.getAdults() + r.getMinors()) != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Si la cantidad de habitaciones es igual a la cantidad de pasajeros, debe haber 1 pasajero por habitación");
                }
            }
        }

        if (totalAssigned != totalPassengers) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La suma de adultos/menores debe ser igual a la cantidad total de pasajeros del grupo");
        }

        accommodationRoomRepo.deleteByAccommodationService_Id(accommodation.getId());
        accommodationRoomRepo.saveAll(toSave);
    }

    private String normalizeAccommodationRoomType(String roomType, int occupants) {
        java.util.List<String> allowed = getAllowedAccommodationRoomTypes(occupants);
        if (allowed.isEmpty()) {
            return null;
        }

        if (roomType == null || roomType.isBlank()) {
            return allowed.get(0);
        }

        String key = normalizeRoomTypeKey(roomType);
        for (String a : allowed) {
            if (normalizeRoomTypeKey(a).equals(key)) {
                return a;
            }
        }

        // Tolerar typo que puede venir desde UI.
        if (key.equals(normalizeRoomTypeKey("Matriomonial + Twin"))) {
            for (String a : allowed) {
                if (normalizeRoomTypeKey(a).equals(normalizeRoomTypeKey("Matrimonial + Twin"))) {
                    return a;
                }
            }
        }

        // Si no es válido, default.
        return allowed.get(0);
    }

    private java.util.List<String> getAllowedAccommodationRoomTypes(int occupants) {
        if (occupants <= 1) {
            return java.util.List.of("Single");
        }
        if (occupants == 2) {
            return java.util.List.of("Matrimonial", "Twin");
        }
        if (occupants == 3) {
            return java.util.List.of("Matrimonial + Twin", "Twin");
        }
        if (occupants == 4) {
            return java.util.List.of("Dos Matrimoniales", "Matrimonial + Twin", "Twin");
        }
        // 5 o más
        return java.util.List.of("Dos Matrimoniales + Twin", "Matrimonial + Twin", "Twin");
    }

    private String normalizeRoomTypeKey(String s) {
        if (s == null) return "";
        // Normalización simple (espacios y case) para comparar.
        return s
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }


    private LocalTime fetchAccommodationDefaultCheckTime(Long accommodationId, boolean checkIn) {
        if (accommodationId == null) return null;

        final java.util.List<String> attempts = checkIn
                ? java.util.List.of(
                "SELECT axh.check_in_time FROM alojamiento_x_horario axh WHERE axh.id_alojamiento = ?",
                "SELECT a.check_in_time FROM alojamientos a WHERE a.id = ? AND a.activo = 1",
                "SELECT a.hora_check_in FROM alojamientos a WHERE a.id = ? AND a.activo = 1",
                "SELECT a.checkin_time FROM alojamientos a WHERE a.id = ? AND a.activo = 1"
        )
                : java.util.List.of(
                "SELECT axh.check_out_time FROM alojamiento_x_horario axh WHERE axh.id_alojamiento = ?",
                "SELECT a.check_out_time FROM alojamientos a WHERE a.id = ? AND a.activo = 1",
                "SELECT a.hora_check_out FROM alojamientos a WHERE a.id = ? AND a.activo = 1",
                "SELECT a.checkout_time FROM alojamientos a WHERE a.id = ? AND a.activo = 1"
        );

        for (String sql : attempts) {
            try {
                java.util.List<LocalTime> rows = jdbc.query(sql, (rs, rowNum) -> normalizeAccommodationTimeValue(rs.getObject(1)), accommodationId);
                if (!rows.isEmpty() && rows.get(0) != null) {
                    return rows.get(0);
                }
            } catch (Exception ignored) {
                // siguiente query
            }
        }

        return null;
    }

    private LocalTime normalizeAccommodationTimeValue(Object value) {
        if (value == null) return null;
        if (value instanceof LocalTime localTime) return localTime.withSecond(0).withNano(0);
        String raw = value.toString().trim();
        if (raw.isBlank()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(raw);
        if (!matcher.find()) return null;
        return LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /**
     * Resuelve el ID numérico de un proveedor a partir de su nombre.
     * Usado para compatibilidad con el Admin Panel que guarda proveedor como string.
     */
    private Long fetchAccommodationProviderIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM proveedores_alojamientos WHERE LOWER(TRIM(nombre)) = LOWER(TRIM(?)) AND activo = 1 LIMIT 1",
                    Long.class,
                    name.trim()
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resuelve el ID numérico de un alojamiento/prestador a partir de su nombre.
     * Usado para compatibilidad con el Admin Panel que guarda prestador como string.
     */
    private Long fetchAccommodationIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM alojamientos WHERE LOWER(TRIM(descripcion)) = LOWER(TRIM(?)) AND activo = 1 LIMIT 1",
                    Long.class,
                    name.trim()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchAccommodationProviderNameById(Long providerId) {
        if (providerId == null) return null;
        try {
            String name = jdbc.queryForObject(
                    "SELECT nombre FROM proveedores_alojamientos WHERE id = ?",
                    String.class,
                    providerId
            );
            return (name != null && !name.isBlank()) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchAccommodationNameById(Long accommodationId) {
        if (accommodationId == null) return null;
        try {
            String name = jdbc.queryForObject(
                    "SELECT descripcion FROM alojamientos WHERE id = ?",
                    String.class,
                    accommodationId
            );
            return (name != null && !name.isBlank()) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }


    // ---------- ADICIONALES ----------

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getAdicionales(Long groupId, Long menuItemId) {
        app.coincidir.api.domain.GroupServiceMenuItem item = menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new app.coincidir.api.common.exception.NotFoundException("Servicio no encontrado"));
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("menuItemId", item.getId());
        result.put("groupId", groupId);
        result.put("notes", item.getNotes());
        result.put("quotedValue", item.getQuotedValue());
        // Usar updated_at como proxy para quotedAt (se actualiza al guardar la cotización)
        result.put("quotedAt", item.getUpdatedAt() != null ? item.getUpdatedAt().toString() : null);
        // Usar operation_status_updated_at como proxy para totalCostUpdatedAt.
        // Solo se retorna si el estado es PAGADO — si no hay pagos (PENDIENTE), va null.
        boolean isPagado = item.getOperationStatus() != null
                && "PAGADO".equalsIgnoreCase(item.getOperationStatus().name());
        result.put("totalCostUpdatedAt", isPagado && item.getOperationStatusUpdatedAt() != null
                ? item.getOperationStatusUpdatedAt().toString()
                : null);
        return result;
    }

    @Transactional
    public java.util.Map<String, Object> upsertAdicionales(Long groupId, Long menuItemId, java.util.Map<String, Object> body) {
        app.coincidir.api.domain.GroupServiceMenuItem item = menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new app.coincidir.api.common.exception.NotFoundException("Servicio no encontrado"));
        if (body.containsKey("notes")) {
            Object v = body.get("notes");
            item.setNotes(v != null ? v.toString().trim() : null);
        }
        if (body.containsKey("quotedValue")) {
            try {
                Object v = body.get("quotedValue");
                item.setQuotedValue(v != null && !v.toString().isBlank()
                        ? new java.math.BigDecimal(v.toString())
                        : null);
            } catch (Exception ignored) {}
        }
        menuItemRepo.save(item);
        return getAdicionales(groupId, menuItemId);
    }

}
