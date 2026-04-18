package app.coincidir.api.service;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.*;
import app.coincidir.api.repository.*;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberOptionalServicesService {

    private final AuthorizationCodeService authCodeService;

    private final TravelRequestRepository travelRequestRepo;

    private final MemberOptionalServiceMenuItemRepository optionalMenuRepo;
    private final MemberOptionalExcursionServiceRepository optionalExcursionRepo;
    private final MemberOptionalTravelAssistanceServiceRepository optionalAssistanceRepo;
    private final MemberOptionalLuggageServiceRepository optionalLuggageRepo;

    private final ExcursionCatalogRepository excursionCatalogRepo;
    private final PrestadorRepository prestadorRepo;
    private final PrestadorLookupDao prestadorLookup;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ------------------------
    // Helpers
    // ------------------------

    private TravelRequest getMemberOrThrowAndValidate(Long groupId, Long memberId) {
        TravelRequest member = travelRequestRepo.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Miembro no encontrado: " + memberId));

        if (member.getGroup() == null || member.getGroup().getId() == null || !member.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El miembro no pertenece al grupo");
        }
        return member;
    }

    private MemberOptionalServiceMenuItem getOptionalMenuItemOrThrowAndValidate(Long groupId, Long memberId, Long menuItemId) {
        // valida miembro primero (y pertenencia al grupo)
        getMemberOrThrowAndValidate(groupId, memberId);

        return optionalMenuRepo.findByIdAndMemberId(menuItemId, memberId)
                .orElseThrow(() -> new NotFoundException("Item opcional no encontrado: " + menuItemId));
    }

    private LocalDate parseNullableDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value, DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha inválida (yyyy-MM-dd): " + value);
        }
    }

    private LocalTime parseNullableTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value, TIME_FMT);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hora inválida (HH:mm): " + value);
        }
    }

    private String formatDate(LocalDate date) {
        return date == null ? null : DATE_FMT.format(date);
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : TIME_FMT.format(time);
    }

    private String baseLabel(MemberOptionalServiceCode code) {
        return switch (code) {
            case EXCURSIONES -> "Excursiones";
            case ASISTENCIA_VIAJERO -> "Asistencia al viajero";
            case EQUIPAJE -> "Equipaje";
        };
    }

    private MemberOptionalServiceMenuItemDto toDto(MemberOptionalServiceMenuItem e) {
        return MemberOptionalServiceMenuItemDto.builder()
                .id(e.getId())
                .serviceCode(e.getServiceCode())
                .displayName(e.getDisplayName())
                .position(e.getPosition())
                .build();
    }

    // ------------------------
    // Menu (opcional)
    // ------------------------

    @Transactional(readOnly = true)
    public MemberOptionalServiceMenuDto getOptionalMenu(Long groupId, Long memberId) {
        getMemberOrThrowAndValidate(groupId, memberId);
        List<MemberOptionalServiceMenuItemDto> items = optionalMenuRepo.findByMemberIdOrderByPositionAsc(memberId)
                .stream().map(this::toDto).toList();

        return MemberOptionalServiceMenuDto.builder().items(items).build();
    }

    public MemberOptionalServiceMenuItemDto addOptionalMenuItem(Long groupId, Long memberId, CreateMemberOptionalServiceMenuItemRequest body) {
        TravelRequest member = getMemberOrThrowAndValidate(groupId, memberId);

        if (body == null || body.getServiceCode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceCode es requerido");
        }

        MemberOptionalServiceCode code = body.getServiceCode();
        int maxPos = optionalMenuRepo.findMaxPositionByMemberId(memberId);
        long sameCodeCount = optionalMenuRepo.countByMemberIdAndServiceCode(memberId, code);

        String base = baseLabel(code);
        String displayName = sameCodeCount == 0 ? base : (base + " " + (sameCodeCount + 1));

        MemberOptionalServiceMenuItem item = new MemberOptionalServiceMenuItem();
        item.setMember(member);
        item.setServiceCode(code);
        item.setDisplayName(displayName);
        item.setPosition(maxPos + 1);

        MemberOptionalServiceMenuItem saved = optionalMenuRepo.save(item);
        return toDto(saved);
    }

    public void deleteOptionalMenuItem(Long groupId, Long memberId, Long itemId) {
        // Acción sensible: requiere autorización si el usuario NO es ADMIN
        authCodeService.requireAuthorizationIfNotAdmin("ADMIN");
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        // borrar datos asociados (si existen)
        optionalExcursionRepo.deleteByMenuItemId(itemId);
        optionalAssistanceRepo.deleteByMenuItemId(itemId);
        optionalLuggageRepo.deleteByMenuItemId(itemId);

        optionalMenuRepo.delete(item);
    }


    // ------------------------
    // EXCURSIONES
    // ------------------------

    private MemberOptionalExcursionDto toExcursionDto(MemberOptionalExcursionService e) {
        ExcursionCatalog ex = e.getExcursion();
        Prestador pr = e.getPrestador();

        var cost = (ex != null ? ex.getCostoUsd() : e.getCost());
        String name;
        if (ex != null) {
            name = (ex.getNombre() != null && !ex.getNombre().isBlank())
                    ? ex.getNombre().trim()
                    : (ex.getDescripcion() != null ? ex.getDescripcion().trim() : null);
        } else {
            name = e.getName();
        }
        String providerName = (pr != null ? pr.getNombre() : null);
        String provider = (pr != null ? pr.getNombre() : e.getProvider());

        String time = formatTime(e.getExcursionTime());
        if (time == null && ex != null && ex.getHorarioSalida() != null) {
            time = formatTime(ex.getHorarioSalida());
        }
        String retTime = formatTime(e.getExcursionReturnTime());
        if (retTime == null && ex != null && ex.getHorarioRegreso() != null) {
            retTime = formatTime(ex.getHorarioRegreso());
        }

        return MemberOptionalExcursionDto.builder()
                .excursionId(ex != null ? ex.getId() : null)
                .name(name)
                .date(formatDate(e.getExcursionDate()))
                .time(time)
                .returnTime(retTime)
                .cost(cost)
                .sale(e.getSale())
                .paymentMethod(e.getPaymentMethod())
                .providerId(pr != null ? pr.getId() : null)
                .providerName(providerName)
                .provider(provider)
                .notes(e.getNotes())
                .build();
    }

    @Transactional(readOnly = true)
    public MemberOptionalExcursionDto getExcursions(Long groupId, Long memberId, Long itemId) {
        getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        return optionalExcursionRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .map(this::toExcursionDto)
                .orElse(null);
    }

    public MemberOptionalExcursionDto upsertExcursions(Long groupId, Long memberId, Long itemId, MemberOptionalExcursionDto body) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body es requerido");
        }

        MemberOptionalExcursionService entity = optionalExcursionRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .orElseGet(MemberOptionalExcursionService::new);

        entity.setMenuItem(item);
        entity.setMember(item.getMember());
        entity.setExcursionDate(parseNullableDate(body.getDate()));
        entity.setExcursionTime(parseNullableTime(body.getTime()));
        entity.setExcursionReturnTime(parseNullableTime(body.getReturnTime()));
        entity.setNotes(body.getNotes());
        entity.setCost(body.getCost());
        entity.setSale(body.getSale());

        // Excursión: preferentemente por excursionId (catálogo)
        if (body.getExcursionId() != null) {
            ExcursionCatalog ex = excursionCatalogRepo.findById(body.getExcursionId())
                    .orElseThrow(() -> new NotFoundException("Excursión no encontrada: " + body.getExcursionId()));
            entity.setExcursion(ex);
                        String exName = (ex.getNombre() != null && !ex.getNombre().isBlank())
                    ? ex.getNombre().trim()
                    : (ex.getDescripcion() != null ? ex.getDescripcion().trim() : "");
            entity.setName(exName);

            // se guarda también para snapshot/compatibilidad
            entity.setCost(ex.getCostoUsd());

            // si no vinieron tiempos desde FE, toma los del catálogo
            if (entity.getExcursionTime() == null && ex.getHorarioSalida() != null) {
                entity.setExcursionTime(ex.getHorarioSalida());
            }
            if (entity.getExcursionReturnTime() == null && ex.getHorarioRegreso() != null) {
                entity.setExcursionReturnTime(ex.getHorarioRegreso());
            }
        } else {
            // Compatibilidad: si no viene id, usa name manual
            if (body.getName() == null || body.getName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "excursionId o name es requerido");
            }
            entity.setExcursion(null);
            entity.setName(body.getName().trim());
            // legacy: si aun se manda cost y entity está vacío, lo toma
            if (entity.getCost() == null && body.getCost() != null) {
                entity.setCost(body.getCost());
            }
        }

        entity.setSale(body.getSale());
        entity.setPaymentMethod(body.getPaymentMethod());

        // Prestador: por providerId (tabla) o fallback string
        if (body.getProviderId() != null) {
            // si hay excursión seleccionada, valida que el prestador esté asociado a esa excursión
            if (entity.getExcursion() != null) {
                boolean ok = prestadorLookup.existsMapping(entity.getExcursion().getId(), body.getProviderId());
                if (!ok) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El prestador no corresponde a la excursión elegida");
                }
            }
            Prestador pr = prestadorRepo.findById(body.getProviderId())
                    .orElseThrow(() -> new NotFoundException("Prestador no encontrado: " + body.getProviderId()));
            entity.setPrestador(pr);
            entity.setProvider(pr.getNombre()); // compat
        } else {
            entity.setPrestador(null);
            entity.setProvider(body.getProvider() != null && !body.getProvider().isBlank() ? body.getProvider().trim() : null);
        }

        MemberOptionalExcursionService saved = optionalExcursionRepo.save(entity);
        return toExcursionDto(saved);
    }

    // ------------------------
    // ASISTENCIA AL VIAJERO
    // ------------------------

    @Transactional(readOnly = true)
    public MemberOptionalTravelAssistanceDto getTravelAssistance(Long groupId, Long memberId, Long itemId) {
        getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        return optionalAssistanceRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .map(e -> MemberOptionalTravelAssistanceDto.builder()
                        .provider(e.getProvider())
                        .plan(e.getPlan())
                        .policyNumber(e.getPolicyNumber())
                        .emergencyPhone(e.getEmergencyPhone())
                        .notes(e.getNotes())
                        .cost(e.getCost())
                        .sale(e.getSale())
                        .build()
                )
                .orElse(null);
    }

    public MemberOptionalTravelAssistanceDto upsertTravelAssistance(Long groupId, Long memberId, Long itemId, MemberOptionalTravelAssistanceDto body) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        if (body == null || body.getProvider() == null || body.getProvider().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider es requerido");
        }

        MemberOptionalTravelAssistanceService entity = optionalAssistanceRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .orElseGet(MemberOptionalTravelAssistanceService::new);

        entity.setMenuItem(item);
        entity.setMember(item.getMember());
        entity.setProvider(body.getProvider().trim());
        entity.setPlan(body.getPlan());
        entity.setPolicyNumber(body.getPolicyNumber());
        entity.setEmergencyPhone(body.getEmergencyPhone());
        entity.setNotes(body.getNotes());
        entity.setCost(body.getCost());
        entity.setSale(body.getSale());

        MemberOptionalTravelAssistanceService saved = optionalAssistanceRepo.save(entity);

        return MemberOptionalTravelAssistanceDto.builder()
                .provider(saved.getProvider())
                .plan(saved.getPlan())
                .policyNumber(saved.getPolicyNumber())
                .emergencyPhone(saved.getEmergencyPhone())
                .notes(saved.getNotes())
                .cost(saved.getCost())
                .sale(saved.getSale())
                .build();
    }

    // ------------------------
    // EQUIPAJE
    // ------------------------

    @Transactional(readOnly = true)
    public MemberOptionalLuggageDto getLuggage(Long groupId, Long memberId, Long itemId) {
        getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        return optionalLuggageRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .map(e -> MemberOptionalLuggageDto.builder()
                        .type(e.getType())
                        .airline(e.getAirline())
                        .baggageRuleId(e.getBaggageRuleId())
                        .weightKg(e.getWeightKg())
                        .dimensions(e.getDimensions())
                        .notes(e.getNotes())
                        .cost(e.getCost())
                        .sale(e.getSale())
                        .build()
                )
                .orElse(null);
    }

    public MemberOptionalLuggageDto upsertLuggage(Long groupId, Long memberId, Long itemId, MemberOptionalLuggageDto body) {
        MemberOptionalServiceMenuItem item = getOptionalMenuItemOrThrowAndValidate(groupId, memberId, itemId);

        if (body == null || body.getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type es requerido");
        }

        MemberOptionalLuggageService entity = optionalLuggageRepo.findByMenuItemIdAndMemberId(itemId, memberId)
                .orElseGet(MemberOptionalLuggageService::new);

        entity.setMenuItem(item);
        entity.setMember(item.getMember());
        entity.setType(body.getType());
        entity.setAirline(body.getAirline());
        entity.setBaggageRuleId(body.getBaggageRuleId());
        entity.setWeightKg(body.getWeightKg());
        entity.setDimensions(body.getDimensions());
        entity.setNotes(body.getNotes());
        entity.setCost(body.getCost());
        entity.setSale(body.getSale());

        MemberOptionalLuggageService saved = optionalLuggageRepo.save(entity);

        return MemberOptionalLuggageDto.builder()
                .type(saved.getType())
                .airline(saved.getAirline())
                .baggageRuleId(saved.getBaggageRuleId())
                .weightKg(saved.getWeightKg())
                .dimensions(saved.getDimensions())
                .notes(saved.getNotes())
                .cost(saved.getCost())
                .sale(saved.getSale())
                .build();
    }
}
