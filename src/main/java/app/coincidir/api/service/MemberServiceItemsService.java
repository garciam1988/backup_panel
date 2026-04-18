// src/main/java/app/coincidir/api/service/MemberServiceItemsService.java
package app.coincidir.api.service;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.*;
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.math.BigDecimal;
import java.util.List;
@Service
@RequiredArgsConstructor
public class MemberServiceItemsService {

    private final AuthorizationCodeService authCodeService;

    private final JdbcTemplate jdbc;

    private final GroupServiceMenuItemRepository menuItemRepo;
    private final TravelRequestRepository travelRequestRepo;

    private final GroupFerryServiceRepository groupFerryRepo;
    private final GroupTransferServiceRepository groupTransferRepo;
    private final GroupDestinationTransferServiceRepository groupDestinationTransferRepo;
    private final GroupAccommodationServiceRepository groupAccommodationRepo;
    private final GroupAccommodationRoomRepository accommodationRoomRepo;
    private final GroupAirServiceRepository groupAirRepo;

    private final TravelRequestAirServiceRepository requestAirRepo;

    private final MemberFerryServiceRepository memberFerryRepo;
    private final MemberTransferServiceRepository memberTransferRepo;
    private final MemberDestinationTransferServiceRepository memberDestinationTransferRepo;
    private final MemberAccommodationServiceRepository memberAccommodationRepo;
    private final MemberAirServiceRepository memberAirRepo;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ------------------------
    // Helpers
    // ------------------------

    private GroupServiceMenuItem getMenuItemOrThrow(Long groupId, Long menuItemId) {
        return menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new NotFoundException("Service menu item no encontrado: " + menuItemId + " (grupo " + groupId + ")"));
    }

    private void assertServiceCode(GroupServiceMenuItem menuItem, ServiceCode expected) {
        ServiceCode actual = menuItem.getService() != null ? menuItem.getService().getCode() : null;
        if (actual != expected) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El item " + menuItem.getId() + " no es del tipo esperado. Esperado: " + expected + ", actual: " + actual
            );
        }
    }

    private TravelRequest getMemberOrThrowAndValidate(Long groupId, Long memberId) {
        TravelRequest member = travelRequestRepo.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Miembro no encontrado: " + memberId));

        if (member.getGroup() == null || member.getGroup().getId() == null || !member.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El miembro no pertenece al grupo");
        }
        return member;
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
 * Actualiza quotedValue / quotedAt solo si cambia el valor.
 * - Updates parciales pueden NO enviar cotización. En ese caso, no se modifica.
 * - Si cambia: quotedAt = now.
 */
private void applyQuoteIfChanged(
        BigDecimal requestedValue,
        BigDecimal currentValue,
        Instant currentAt,
        java.util.function.Consumer<BigDecimal> setValue,
        java.util.function.Consumer<Instant> setAt
) {
    // El front puede enviar updates parciales (sin re-enviar cotización).
    // En ese caso requestedValue llega null y NO debe interpretarse como "limpiar".
    if (requestedValue == null) return;

    // Cotización: primera carga NO requiere; modificación sí (si no es ADMIN)
    authCodeService.requireAuthorizationForQuoteChangeIfNeeded(currentValue, requestedValue);

    boolean changed = currentValue == null || currentValue.compareTo(requestedValue) != 0;
    if (changed) {
        setValue.accept(requestedValue);
        setAt.accept(Instant.now());
    } else if (currentAt == null) {
        // por consistencia, si hay valor pero no hay fecha, la completamos
        setAt.accept(Instant.now());
    }
}

    // ------------------------
    // Mappers (Member -> DTO)
    // ------------------------

    private GroupFerryServiceDto toDto(MemberFerryService e, Long groupId) {
        return GroupFerryServiceDto.builder()
                .id(e.getId())
                .groupId(groupId)
                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .tripType(e.getTripType())
                .originPort(e.getOriginPort())
                .destinationPort(e.getDestinationPort())
                .departureDate(e.getDepartureDate() != null ? e.getDepartureDate().format(DATE_FMT) : null)
                .returnDate(e.getReturnDate() != null ? e.getReturnDate().format(DATE_FMT) : null)
                .ferryCompany(e.getFerryCompany())
                .provider(e.getProvider())
                .reservationCode(e.getReservationCode())
                .departureTime(e.getDepartureTime() != null ? e.getDepartureTime().format(TIME_FMT) : null)
                .returnTime(e.getReturnTime() != null ? e.getReturnTime().format(TIME_FMT) : null)
                .notes(e.getNotes())
                .departureArrivalTime(e.getDepartureArrivalTime() != null ? e.getDepartureArrivalTime().format(TIME_FMT) : null)
                .returnArrivalTime(e.getReturnArrivalTime() != null ? e.getReturnArrivalTime().format(TIME_FMT) : null)
                .busOrigin(e.getBusOrigin())
                .busDestination(e.getBusDestination())
                .busDepartureTime(e.getBusDepartureTime() != null ? e.getBusDepartureTime().format(TIME_FMT) : null)
                .busArrivalTime(e.getBusArrivalTime() != null ? e.getBusArrivalTime().format(TIME_FMT) : null)
                .returnBusDepartureTime(e.getReturnBusDepartureTime() != null ? e.getReturnBusDepartureTime().format(TIME_FMT) : null)
                .returnBusArrivalTime(e.getReturnBusArrivalTime() != null ? e.getReturnBusArrivalTime().format(TIME_FMT) : null)
                .build();
    }

    private GroupTransferServiceDto toDto(MemberTransferService e, Long groupId) {
        return GroupTransferServiceDto.builder()
                .id(e.getId())
                .groupId(groupId)
                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .tripType(e.getTripType() != null ? e.getTripType().name() : null)
                .pickupAddress(e.getPickupPlace())
                .dropoffAddress(e.getDestinationPlace())
                .departureDate(e.getDepartureDate() != null ? e.getDepartureDate().toString() : null)
                .departureTime(e.getDepartureTime() != null ? e.getDepartureTime().format(TIME_FMT) : null)
                .returnDate(e.getReturnDate() != null ? e.getReturnDate().toString() : null)
                .returnTime(e.getReturnTime() != null ? e.getReturnTime().format(TIME_FMT) : null)
                .notes(e.getNotes())
                .provider(e.getProvider())
                .reservationCode(e.getReservationCode())
                .departureArrivalTime(e.getDepartureArrivalTime() != null ? e.getDepartureArrivalTime().format(TIME_FMT) : null)
                .returnArrivalTime(e.getReturnArrivalTime() != null ? e.getReturnArrivalTime().format(TIME_FMT) : null)
                .build();
    }

    private GroupDestinationTransferServiceDto toDto(MemberDestinationTransferService e, Long groupId) {
        return GroupDestinationTransferServiceDto.builder()
                .id(e.getId())
                .groupId(groupId)
                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .tripType(e.getTripType() != null ? e.getTripType().name() : null)
                .pickupAddress(e.getPickupPlace())
                .dropoffAddress(e.getDestinationPlace())
                .departureDate(e.getDepartureDate() != null ? e.getDepartureDate().toString() : null)
                .departureTime(e.getDepartureTime() != null ? e.getDepartureTime().format(TIME_FMT) : null)
                .returnDate(e.getReturnDate() != null ? e.getReturnDate().toString() : null)
                .returnTime(e.getReturnTime() != null ? e.getReturnTime().format(TIME_FMT) : null)
                .notes(e.getNotes())
                .provider(e.getProvider())
                .reservationCode(e.getReservationCode())
                .country(e.getCountry())
                .city(e.getCity())
                .departureArrivalTime(e.getDepartureArrivalTime() != null ? e.getDepartureArrivalTime().format(TIME_FMT) : null)
                .returnArrivalTime(e.getReturnArrivalTime() != null ? e.getReturnArrivalTime().format(TIME_FMT) : null)
                .build();
    }

    private GroupAccommodationServiceDto toDto(MemberAccommodationService e, Long groupId) {
        return GroupAccommodationServiceDto.builder()
                .id(e.getId())
                .groupId(groupId)
                .quotedValue(e.getQuotedValue())
                .quotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null)
                .name(e.getName())
                .checkInDate(e.getCheckInDate() != null ? e.getCheckInDate().toString() : null)
                .checkInTime(e.getCheckInTime() != null ? e.getCheckInTime().format(TIME_FMT) : null)
                .checkOutDate(e.getCheckOutDate() != null ? e.getCheckOutDate().toString() : null)
                .checkOutTime(e.getCheckOutTime() != null ? e.getCheckOutTime().format(TIME_FMT) : null)
                .regimen(e.getRegimen())
                .country(e.getCountry())
                .city(e.getCity())
                .contractType(e.getContractType())
                .thirdPartyName(e.getThirdPartyName())
                .provider(e.getThirdPartyName())
                .providerId(e.getProviderId())
                .accommodationId(e.getAccommodationId())
                .reservationCode(e.getReservationCode())
                .build();
    }


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
                            .build())
                    .toList());
        } catch (Exception ignored) {
        }
    }

    private AirServiceDto toDto(MemberAirService e, Long groupId) {
        AirServiceDto dto = new AirServiceDto();
        dto.setId(e.getId());
        dto.setGroupId(groupId);

        dto.setQuotedValue(e.getQuotedValue());
        dto.setQuotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null);

        dto.setTripType(e.getTripType());
        dto.setOrigin(e.getOrigin());
        dto.setDestination(e.getDestination());

        dto.setAirline(e.getAirline());

        dto.setDepartureDate(e.getDepartureDate());
        dto.setDepartureTime(e.getDepartureTime());
        dto.setDepartureArrivalTime(e.getDepartureArrivalTime());

        dto.setReturnDate(e.getReturnDate());
        dto.setReturnDepartureTime(e.getReturnDepartureTime());
        dto.setReturnArrivalTime(e.getReturnArrivalTime());

        dto.setBaggageAllowance(e.getBaggageAllowance());
        dto.setReservationCode(e.getReservationCode());
        return dto;
    }

    private AirServiceDto toDto(GroupAirService e) {
        AirServiceDto dto = new AirServiceDto();
        dto.setId(e.getId());
        dto.setGroupId(
                e.getMenuItem() != null && e.getMenuItem().getGroup() != null ? e.getMenuItem().getGroup().getId() : null
        );

        dto.setQuotedValue(e.getQuotedValue());
        dto.setQuotedAt(e.getQuotedAt() != null ? e.getQuotedAt().toString() : null);

        dto.setTripType(e.getTripType());
        dto.setOrigin(e.getOrigin());
        dto.setDestination(e.getDestination());

        dto.setAirline(e.getAirline());

        dto.setDepartureDate(e.getDepartureDate());
        dto.setDepartureTime(e.getDepartureTime());
        dto.setDepartureArrivalTime(e.getDepartureArrivalTime());

        dto.setReturnDate(e.getReturnDate());
        dto.setReturnDepartureTime(e.getReturnDepartureTime());
        dto.setReturnArrivalTime(e.getReturnArrivalTime());

        dto.setBaggageAllowance(e.getBaggageAllowance());
        dto.setReservationCode(e.getReservationCode());
        return dto;
    }

    // ============================================================
    // FERRY (member)
    // ============================================================

    @Transactional(readOnly = true)
    public GroupFerryServiceDto getFerry(Long groupId, Long memberId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.FERRY);
        getMemberOrThrowAndValidate(groupId, memberId);

        return memberFerryRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .map(e -> toDto(e, groupId))
                .orElseGet(() ->
                        groupFerryRepo.findByMenuItemId(menuItemId)
                                .map(GroupFerryServiceDto::fromEntity)
                                .orElse(null)
                );
    }

    @Transactional
    public GroupFerryServiceDto upsertFerry(Long groupId, Long memberId, Long menuItemId, UpsertGroupFerryRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.FERRY);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        if (req.getTripType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tripType es obligatorio");
        }

        MemberFerryService entity = memberFerryRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .orElseGet(() -> {
                    MemberFerryService x = new MemberFerryService();
                    x.setMenuItem(menuItem);
                    x.setMember(member);
                    x.setOverridden(false);
                    return x;
                });



// init quoted fields from group service if missing
if (entity.getQuotedValue() == null && entity.getQuotedAt() == null) {
    groupFerryRepo.findByMenuItemId(menuItemId).ifPresent(gs -> {
        entity.setQuotedValue(gs.getQuotedValue());
        entity.setQuotedAt(gs.getQuotedAt());
    });
}
        entity.setOverridden(true);

        // Cotización editable por miembro
        applyQuoteIfChanged(req.getQuotedValue(), entity.getQuotedValue(), entity.getQuotedAt(), entity::setQuotedValue, entity::setQuotedAt);

        entity.setTripType(req.getTripType());
        entity.setOriginPort(nullIfBlank(req.getOriginPort()));
        entity.setDestinationPort(nullIfBlank(req.getDestinationPort()));

        entity.setDepartureDate(parseNullableDate(req.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(req.getDepartureTime()));
        entity.setDepartureArrivalTime(parseNullableTime(req.getDepartureArrivalTime()));

        entity.setReturnDate(parseNullableDate(req.getReturnDate()));
        entity.setReturnTime(parseNullableTime(req.getReturnTime()));
        entity.setReturnArrivalTime(parseNullableTime(req.getReturnArrivalTime()));

        if (entity.getTripType() == FerryTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
            entity.setReturnArrivalTime(null);
        }

        entity.setFerryCompany(nullIfBlank(req.getFerryCompany()));
        entity.setProvider(nullIfBlank(req.getProvider()));

        // Bus fields
        entity.setBusOrigin(nullIfBlank(req.getBusOrigin()));
        entity.setBusDestination(nullIfBlank(req.getBusDestination()));
        entity.setBusDepartureTime(parseNullableTime(req.getBusDepartureTime()));
        entity.setBusArrivalTime(parseNullableTime(req.getBusArrivalTime()));
        if (entity.getTripType() == FerryTripType.ONE_WAY) {
            entity.setReturnBusDepartureTime(null);
            entity.setReturnBusArrivalTime(null);
        } else {
            entity.setReturnBusDepartureTime(parseNullableTime(req.getReturnBusDepartureTime()));
            entity.setReturnBusArrivalTime(parseNullableTime(req.getReturnBusArrivalTime()));
        }

        String rc = nullIfBlank(req.getReservationCode());
        // Si el front no lo re-envía pero ya estaba guardado, conservar el existente.
        if (rc == null) {
            rc = nullIfBlank(entity.getReservationCode());
        }
        if (rc == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código de reserva es obligatorio");
        }
        entity.setReservationCode(rc);
        entity.setNotes(nullIfBlank(req.getNotes()));

        MemberFerryService saved = memberFerryRepo.save(entity);
        return toDto(saved, groupId);
    }

    // ============================================================
    // TRASLADOS BA (member)
    // ============================================================

    @Transactional(readOnly = true)
    public GroupTransferServiceDto getTransfers(Long groupId, Long memberId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS);
        getMemberOrThrowAndValidate(groupId, memberId);

        return memberTransferRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .map(e -> toDto(e, groupId))
                .orElseGet(() ->
                        groupTransferRepo.findByMenuItemId(menuItemId)
                                .map(GroupTransferServiceDto::fromEntity)
                                .orElse(null)
                );
    }

    @Transactional
    public GroupTransferServiceDto upsertTransfers(Long groupId, Long memberId, Long menuItemId, UpsertGroupTransferRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        MemberTransferService entity = memberTransferRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .orElseGet(() -> {
                    MemberTransferService x = new MemberTransferService();
                    x.setMenuItem(menuItem);
                    x.setMember(member);
                    x.setOverridden(false);
                    return x;
                });



if (entity.getQuotedValue() == null && entity.getQuotedAt() == null) {
    groupTransferRepo.findByMenuItemId(menuItemId).ifPresent(gs -> {
        entity.setQuotedValue(gs.getQuotedValue());
        entity.setQuotedAt(gs.getQuotedAt());
    });
}
        entity.setOverridden(true);

        // Cotización editable por miembro
        applyQuoteIfChanged(req.getQuotedValue(), entity.getQuotedValue(), entity.getQuotedAt(), entity::setQuotedValue, entity::setQuotedAt);

        entity.setPickupPlace(nullIfBlank(req.getPickupAddress()));
        entity.setDestinationPlace(nullIfBlank(req.getDropoffAddress()));

        TransferTripType tripType = normalizeTransferTripType(req.getTripType(), parseNullableDate(req.getReturnDate()), parseNullableTime(req.getReturnTime()));
        entity.setTripType(tripType);

        entity.setDepartureDate(parseNullableDate(req.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(req.getDepartureTime()));
        entity.setDepartureArrivalTime(parseNullableTime(req.getDepartureArrivalTime()));

        entity.setReturnDate(parseNullableDate(req.getReturnDate()));
        entity.setReturnTime(parseNullableTime(req.getReturnTime()));
        entity.setReturnArrivalTime(parseNullableTime(req.getReturnArrivalTime()));

        if (tripType == TransferTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
        }

        if (entity.getReturnDate() == null || entity.getReturnTime() == null) {
            entity.setReturnArrivalTime(null);
        }

        entity.setNotes(nullIfBlank(req.getNotes()));
        entity.setProvider(nullIfBlank(req.getProvider()));
        // Código de reserva (legacy): ya no es obligatorio para Traslados BA.
        // Si viene en el request, actualizar; si no, preservar el existente.
        String rc = nullIfBlank(req.getReservationCode());
        if (rc != null) {
            entity.setReservationCode(rc);
        }

        MemberTransferService saved = memberTransferRepo.save(entity);
        return toDto(saved, groupId);
    }

    // ============================================================
    // TRASLADOS DESTINO (member)
    // ============================================================

    @Transactional(readOnly = true)
    public GroupDestinationTransferServiceDto getDestinationTransfers(Long groupId, Long memberId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS_DESTINO);
        getMemberOrThrowAndValidate(groupId, memberId);

        return memberDestinationTransferRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .map(e -> toDto(e, groupId))
                .orElseGet(() ->
                        groupDestinationTransferRepo.findByMenuItemId(menuItemId)
                                .map(GroupDestinationTransferServiceDto::fromEntity)
                                .orElse(null)
                );
    }

    @Transactional
    public GroupDestinationTransferServiceDto upsertDestinationTransfers(Long groupId, Long memberId, Long menuItemId, UpsertGroupTransferRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.TRASLADOS_DESTINO);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        MemberDestinationTransferService entity = memberDestinationTransferRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .orElseGet(() -> {
                    MemberDestinationTransferService x = new MemberDestinationTransferService();
                    x.setMenuItem(menuItem);
                    x.setMember(member);
                    x.setOverridden(false);
                    return x;
                });



if (entity.getQuotedValue() == null && entity.getQuotedAt() == null) {
    groupDestinationTransferRepo.findByMenuItemId(menuItemId).ifPresent(gs -> {
        entity.setQuotedValue(gs.getQuotedValue());
        entity.setQuotedAt(gs.getQuotedAt());
    });
}
        entity.setOverridden(true);

        // Cotización editable por miembro
        applyQuoteIfChanged(req.getQuotedValue(), entity.getQuotedValue(), entity.getQuotedAt(), entity::setQuotedValue, entity::setQuotedAt);

        entity.setPickupPlace(nullIfBlank(req.getPickupAddress()));
        entity.setDestinationPlace(nullIfBlank(req.getDropoffAddress()));

        TransferTripType tripType = normalizeTransferTripType(req.getTripType(), parseNullableDate(req.getReturnDate()), parseNullableTime(req.getReturnTime()));
        entity.setTripType(tripType);

        entity.setDepartureDate(parseNullableDate(req.getDepartureDate()));
        entity.setDepartureTime(parseNullableTime(req.getDepartureTime()));
        entity.setDepartureArrivalTime(parseNullableTime(req.getDepartureArrivalTime()));

        entity.setReturnDate(parseNullableDate(req.getReturnDate()));
        entity.setReturnTime(parseNullableTime(req.getReturnTime()));
        entity.setReturnArrivalTime(parseNullableTime(req.getReturnArrivalTime()));

        if (tripType == TransferTripType.ONE_WAY) {
            entity.setReturnDate(null);
            entity.setReturnTime(null);
        }

        if (entity.getReturnDate() == null || entity.getReturnTime() == null) {
            entity.setReturnArrivalTime(null);
        }

        // country/city vienen en el mismo request usado para destino
        String country = nullIfBlank(req.getCountry());
        entity.setCountry(country != null ? country : "Argentina");
        entity.setCity(nullIfBlank(req.getCity()));

        entity.setProvider(nullIfBlank(req.getProvider()));
        // Código de reserva (legacy): ya no es obligatorio para Traslados Destino.
        // Si viene en el request, actualizar; si no, preservar el existente.
        String rc = nullIfBlank(req.getReservationCode());
        if (rc != null) {
            entity.setReservationCode(rc);
        }

        entity.setNotes(nullIfBlank(req.getNotes()));

        MemberDestinationTransferService saved = memberDestinationTransferRepo.save(entity);
        return toDto(saved, groupId);
    }

    // ============================================================
    // ALOJAMIENTOS (member)
    // ============================================================

    @Transactional(readOnly = true)
    public GroupAccommodationServiceDto getAccommodation(Long groupId, Long memberId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.ALOJAMIENTOS);
        getMemberOrThrowAndValidate(groupId, memberId);

        GroupAccommodationServiceDto dto = memberAccommodationRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .map(e -> toDto(e, groupId))
                .orElseGet(() ->
                        groupAccommodationRepo.findByMenuItemId(menuItemId)
                                .map(GroupAccommodationServiceDto::fromEntity)
                                .orElse(null)
                );

        // Compatibilidad: si hay ids pero no vienen nombres (datos legacy), completar para que el front recupere Proveedor/Prestador.
        if (dto != null) {
            if (dto.getProviderId() != null && (dto.getProvider() == null || dto.getProvider().isBlank())) {
                String providerName = fetchAccommodationProviderNameById(dto.getProviderId());
                if (providerName != null) {
                    dto.setProvider(providerName);
                    dto.setThirdPartyName(providerName);
                }
            }
            if (dto.getAccommodationId() != null && (dto.getName() == null || dto.getName().isBlank())) {
                String accommodationName = fetchAccommodationNameById(dto.getAccommodationId());
                if (accommodationName != null) {
                    dto.setName(accommodationName);
                }
            }
        }

        // Habitaciones: la distribución es a nivel grupo (menu item).
        try {
            Long groupAccId = groupAccommodationRepo.findByMenuItemId(menuItemId)
                    .map(app.coincidir.api.domain.GroupAccommodationService::getId)
                    .orElse(null);
            attachAccommodationRooms(dto, groupAccId);
        } catch (Exception ignored) {
        }

        return dto;
    }

    @Transactional
    public GroupAccommodationServiceDto upsertAccommodation(Long groupId, Long memberId, Long menuItemId, UpsertGroupAccommodationRequest req) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.ALOJAMIENTOS);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        if (req.getName() == null || req.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        if (req.getCheckInDate() == null || req.getCheckInDate().isBlank() || req.getCheckOutDate() == null || req.getCheckOutDate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-in y check-out son obligatorios");
        }
        if (req.getRegimen() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El régimen es obligatorio");
        }

        MemberAccommodationService entity = memberAccommodationRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .orElseGet(() -> {
                    MemberAccommodationService x = new MemberAccommodationService();
                    x.setMenuItem(menuItem);
                    x.setMember(member);
                    x.setOverridden(false);
                    return x;
                });



if (entity.getQuotedValue() == null && entity.getQuotedAt() == null) {
    groupAccommodationRepo.findByMenuItemId(menuItemId).ifPresent(gs -> {
        entity.setQuotedValue(gs.getQuotedValue());
        entity.setQuotedAt(gs.getQuotedAt());
    });
}
        entity.setOverridden(true);

        // Cotización editable por miembro
        applyQuoteIfChanged(req.getQuotedValue(), entity.getQuotedValue(), entity.getQuotedAt(), entity::setQuotedValue, entity::setQuotedAt);

        entity.setName(nullIfBlank(req.getName()));
        entity.setCheckInDate(parseNullableDate(req.getCheckInDate()));
        entity.setCheckOutDate(parseNullableDate(req.getCheckOutDate()));
        entity.setRegimen(req.getRegimen());

        String country = nullIfBlank(req.getCountry());
        entity.setCountry(country != null ? country : "Argentina");
        entity.setCity(nullIfBlank(req.getCity()));

        // Proveedor & Prestador (combos anidados)
        // Nota: si el front no re-envía estos ids en un update parcial, no debemos limpiarlos.
        Long providerId = (req.getProviderId() != null) ? req.getProviderId() : entity.getProviderId();
        Long previousAccommodationId = entity.getAccommodationId();
        Long accommodationId = (req.getAccommodationId() != null) ? req.getAccommodationId() : previousAccommodationId;
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
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Proveedor inválido");
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

        MemberAccommodationService saved = memberAccommodationRepo.save(entity);
        GroupAccommodationServiceDto dto = toDto(saved, groupId);
        try {
            Long groupAccId = groupAccommodationRepo.findByMenuItemId(menuItemId)
                    .map(app.coincidir.api.domain.GroupAccommodationService::getId)
                    .orElse(null);
            attachAccommodationRooms(dto, groupAccId);
        } catch (Exception ignored) {
        }
        return dto;
    }

    // ============================================================
    // AEREOS (member)
    // ============================================================

    @Transactional
    public AirServiceDto getAir(Long groupId, Long memberId, Long menuItemId) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.AEREOS);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        // 1) Si ya existe override a nivel miembro, devolverlo
        java.util.Optional<MemberAirService> existing = memberAirRepo.findByMenuItemIdAndMemberId(menuItemId, memberId);
        if (existing.isPresent()) {
            return toDto(existing.get(), groupId);
        }

        // 2) Si el pasajero tiene precarga, crear override a nivel miembro para que no se pierda
        requestAirRepo.findByRequest_Id(memberId).ifPresent(prefill -> {
            MemberAirService x = new MemberAirService();
            x.setMenuItem(menuItem);
            x.setMember(member);
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

        // Rechequear (por si se creó desde precarga)
        java.util.Optional<MemberAirService> after = memberAirRepo.findByMenuItemIdAndMemberId(menuItemId, memberId);
        if (after.isPresent()) {
            return toDto(after.get(), groupId);
        }

        // 3) Fallback a servicio de grupo
        return groupAirRepo.findByMenuItemId(menuItemId)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public AirServiceDto upsertAir(Long groupId, Long memberId, Long menuItemId, AirServiceDto body) {
        GroupServiceMenuItem menuItem = getMenuItemOrThrow(groupId, menuItemId);
        assertServiceCode(menuItem, ServiceCode.AEREOS);
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        if (body.getOrigin() == null || body.getOrigin().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El origen es obligatorio");
        }
        if (body.getDestination() == null || body.getDestination().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El destino es obligatorio");
        }
        if (body.getAirline() == null || body.getAirline().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La aerolínea es obligatoria");
        }
        if (body.getDepartureDate() == null || body.getDepartureTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha y hora de salida (ida) son obligatorias");
        }

        String tripType = body.getTripType();
        if (tripType == null || tripType.isBlank()) {
            tripType = (body.getReturnDate() != null) ? "ROUND_TRIP" : "ONE_WAY";
        }
        tripType = tripType.toUpperCase();
        if (!tripType.equals("ONE_WAY") && !tripType.equals("ROUND_TRIP")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tripType inválido. Use ONE_WAY o ROUND_TRIP");
        }
        if (tripType.equals("ROUND_TRIP")) {
            if (body.getReturnDate() == null || body.getReturnDepartureTime() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "En ida y vuelta, fecha y hora de salida (regreso) son obligatorias");
            }
        }

        MemberAirService entity = memberAirRepo.findByMenuItemIdAndMemberId(menuItemId, memberId)
                .orElseGet(() -> {
                    MemberAirService x = new MemberAirService();
                    x.setMenuItem(menuItem);
                    x.setMember(member);
                    x.setOverridden(false);
                    return x;
                });



if (entity.getQuotedValue() == null && entity.getQuotedAt() == null) {
    groupAirRepo.findByMenuItemId(menuItemId).ifPresent(gs -> {
        entity.setQuotedValue(gs.getQuotedValue());
        entity.setQuotedAt(gs.getQuotedAt());
    });
}
        entity.setOverridden(true);

        // Cotización editable por miembro
        applyQuoteIfChanged(body.getQuotedValue(), entity.getQuotedValue(), entity.getQuotedAt(), entity::setQuotedValue, entity::setQuotedAt);

        entity.setTripType(tripType);
        entity.setOrigin(body.getOrigin().trim());
        entity.setDestination(body.getDestination().trim());
        entity.setAirline(body.getAirline().trim());

        entity.setDepartureDate(body.getDepartureDate());
        entity.setDepartureTime(body.getDepartureTime());
        entity.setDepartureArrivalTime(body.getDepartureArrivalTime());

        if (tripType.equals("ONE_WAY")) {
            entity.setReturnDate(null);
            entity.setReturnDepartureTime(null);
            entity.setReturnArrivalTime(null);
        } else {
            entity.setReturnDate(body.getReturnDate());
            entity.setReturnDepartureTime(body.getReturnDepartureTime());
            entity.setReturnArrivalTime(body.getReturnArrivalTime());
        }

        entity.setBaggageAllowance(
                (body.getBaggageAllowance() == null || body.getBaggageAllowance().isBlank())
                        ? "6_KG"
                        : body.getBaggageAllowance()
        );

        entity.setReservationCode(
                (body.getReservationCode() == null || body.getReservationCode().isBlank())
                        ? null
                        : body.getReservationCode().trim()
        );

        MemberAirService saved = memberAirRepo.save(entity);
        return toDto(saved, groupId);
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

    private String fetchAccommodationProviderNameById(Long providerId) {
        if (providerId == null) return null;
        try {
            String name = jdbc.queryForObject(
                    "SELECT nombre FROM proveedores_alojamientos WHERE id = ? AND activo = 1",
                    String.class,
                    providerId
            );
            return (name != null && !name.isBlank()) ? name.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchAccommodationNameById(Long accommodationId) {
        if (accommodationId == null) return null;
        try {
            String name = jdbc.queryForObject(
                    "SELECT descripcion FROM alojamientos WHERE id = ? AND activo = 1",
                    String.class,
                    accommodationId
            );
            return (name != null && !name.isBlank()) ? name.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

}