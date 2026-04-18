package app.coincidir.api.service;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.ServiceDefinition;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.TravelRequest;

import app.coincidir.api.repository.GroupAccommodationServiceRepository;
import app.coincidir.api.repository.GroupAccommodationRoomRepository;
import app.coincidir.api.repository.GroupAirServiceRepository;
import app.coincidir.api.repository.GroupDestinationTransferServiceRepository;
import app.coincidir.api.repository.GroupFerryServiceRepository;
import app.coincidir.api.repository.GroupTransferServiceRepository;
import app.coincidir.api.repository.MemberAccommodationServiceRepository;
import app.coincidir.api.repository.MemberAirServiceRepository;
import app.coincidir.api.repository.MemberDestinationTransferServiceRepository;
import app.coincidir.api.repository.MemberFerryServiceRepository;
import app.coincidir.api.repository.MemberTransferServiceRepository;
import app.coincidir.api.repository.GroupServiceMenuItemRepository;
import app.coincidir.api.repository.ServiceDefinitionRepository;
import app.coincidir.api.repository.TravelRequestAirServiceRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GroupServiceMenuService {

    private final AuthorizationCodeService authCodeService;
    private final OperationsRoleGuardService operationsRoleGuardService;

    private final TravelGroupRepository groupRepo;
    private final ServiceDefinitionRepository serviceRepo;
    private final GroupServiceMenuItemRepository menuRepo;

    private final GroupFerryServiceRepository groupFerryServiceRepository;
    private final MemberFerryServiceRepository memberFerryServiceRepository;
    private final GroupTransferServiceRepository groupTransferServiceRepository;
    private final MemberTransferServiceRepository memberTransferServiceRepository;
    private final GroupDestinationTransferServiceRepository groupDestinationTransferServiceRepository;
    private final MemberDestinationTransferServiceRepository memberDestinationTransferServiceRepository;
    private final GroupAccommodationServiceRepository groupAccommodationServiceRepository;
    private final GroupAccommodationRoomRepository groupAccommodationRoomRepository;
    private final MemberAccommodationServiceRepository memberAccommodationServiceRepository;
    private final GroupAirServiceRepository groupAirServiceRepository;
    private final MemberAirServiceRepository memberAirServiceRepository;

    // Para bootstrap automático de Aéreos desde precarga del pasajero
    private final TravelRequestRepository travelRequestRepo;
    private final TravelRequestAirServiceRepository requestAirRepo;
    private final GroupServiceItemsService groupServiceItemsService;

    @Transactional
    public GroupServiceMenuDto getMenu(Long groupId) {
        // Si algún miembro tiene precarga de Aéreos, asegurar que exista el item en el menú del grupo
        // y bootstrapping para que se visualice automáticamente al habilitar Servicios.
        bootstrapAirMenuIfNeeded(groupId);

        List<GroupServiceMenuItemDto> items = menuRepo.findByGroupIdOrderByPositionAsc(groupId)
                .stream()
                .map(GroupServiceMenuItemDto::fromEntity)
                .toList();
        return GroupServiceMenuDto.builder()
                .groupId(groupId)
                .items(items)
                .build();
    }

    private void bootstrapAirMenuIfNeeded(Long groupId) {
        // 1) ¿Hay miembros con precarga de aéreos?
        List<Long> memberIds = travelRequestRepo.findByGroupIdOrderByIdAsc(groupId)
                .stream()
                .map(TravelRequest::getId)
                .toList();

        if (memberIds.isEmpty()) return;
        if (!requestAirRepo.existsByRequest_IdIn(memberIds)) return;

        // 2) Asegurar item AEREOS en el menú
        GroupServiceMenuItem menuItem = menuRepo
                .findFirstByGroupIdAndService_CodeOrderByPositionAsc(groupId, ServiceCode.AEREOS)
                .orElseGet(() -> {
                    TravelGroup group = groupRepo.findById(groupId)
                            .orElseThrow(() -> new NotFoundException("Grupo no encontrado: " + groupId));

                    ServiceDefinition service = serviceRepo.findByCode(ServiceCode.AEREOS)
                            .orElseThrow(() -> new NotFoundException("Servicio AEREOS no encontrado"));

                    int nextPosition = menuRepo.findMaxPosition(groupId) + 1;
                    long existingCount = menuRepo.countByGroupIdAndServiceCode(groupId, ServiceCode.AEREOS);
                    String displayName = (existingCount <= 0)
                            ? service.getName()
                            : service.getName() + " " + (existingCount + 1);

                    GroupServiceMenuItem item = new GroupServiceMenuItem();
                    item.setGroup(group);
                    item.setService(service);
                    item.setPosition(nextPosition);
                    item.setDisplayName(displayName);
                    return menuRepo.save(item);
                });

        // 3) Si todavía no existe el servicio de grupo, crearlo usando una precarga como plantilla.
        // Esto dispara el sync a miembros y preserva overrides (precargas) automáticamente.
        if (groupAirServiceRepository.findByMenuItemId(menuItem.getId()).isEmpty()) {
            var prefillList = requestAirRepo.findAllByRequest_IdIn(memberIds);
            if (prefillList == null || prefillList.isEmpty()) return;

            var prefill = prefillList.get(0);

            AirServiceDto dto = new AirServiceDto();
            dto.setQuotedValue(prefill.getQuotedValue());
            dto.setTripType(prefill.getTripType());
            dto.setOrigin(prefill.getOrigin());
            dto.setDestination(prefill.getDestination());
            dto.setAirline(prefill.getAirline());
            dto.setDepartureDate(prefill.getDepartureDate());
            dto.setDepartureTime(prefill.getDepartureTime());
            dto.setDepartureArrivalTime(prefill.getDepartureArrivalTime());
            dto.setReturnDate(prefill.getReturnDate());
            dto.setReturnDepartureTime(prefill.getReturnDepartureTime());
            dto.setReturnArrivalTime(prefill.getReturnArrivalTime());
            dto.setBaggageAllowance(prefill.getBaggageAllowance());

            // Bootstrap interno: crea servicio de grupo y sincroniza a miembros.
            // No debe bloquear a OPERATIONS si el grupo aún tiene pagos sin conciliar.
            groupServiceItemsService.upsertAirBootstrap(groupId, menuItem.getId(), dto);
        }
    }

    @Transactional
    public GroupServiceMenuItemDto addMenuItem(Long groupId, CreateGroupServiceMenuItemRequest req) {
        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado: " + groupId));

        // Si la operación ya está confirmada, no permitir agregar más servicios.
        if (group.isOperationConfirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pueden agregar servicios: la operación ya está confirmada.");
        }

        ServiceDefinition service = serviceRepo.findById(req.serviceId())
                .orElseThrow(() -> new NotFoundException("Servicio no encontrado: " + req.serviceId()));

        int nextPosition = menuRepo.findMaxPosition(groupId) + 1;

        // Nombre visible: "Ferry", "Ferry 2", "Ferry 3"...
        long existingCount = menuRepo.countByGroupIdAndServiceCode(groupId, service.getCode());
        String displayName = (existingCount <= 0)
                ? service.getName()
                : service.getName() + " " + (existingCount + 1);

        GroupServiceMenuItem item = new GroupServiceMenuItem();
        item.setGroup(group);
        item.setService(service);
        item.setPosition(nextPosition);
        item.setDisplayName(displayName);

        GroupServiceMenuItem saved = menuRepo.save(item);
        return GroupServiceMenuItemDto.fromEntity(saved);
    }

    @Transactional
    public GroupServiceMenuDto updateOrder(Long groupId, UpdateGroupServiceMenuOrderRequest req) {
        List<GroupServiceMenuItem> current = menuRepo.findByGroupIdOrderByPositionAsc(groupId);
        if (current.isEmpty()) {
            return getMenu(groupId);
        }

        List<Long> ordered = req.orderedItemIds();
        if (ordered == null || ordered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderedItemIds no puede estar vacío");
        }

        if (ordered.size() != current.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderedItemIds debe contener todos los items del menú");
        }

        Map<Long, GroupServiceMenuItem> byId = new HashMap<>();
        for (GroupServiceMenuItem item : current) {
            byId.put(item.getId(), item);
        }

        // Validar que todos existan y pertenezcan al grupo
        for (Long id : ordered) {
            if (!byId.containsKey(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El item no pertenece al grupo: " + id);
            }
        }

        int pos = 1;
        for (Long id : ordered) {
            GroupServiceMenuItem item = byId.get(id);
            item.setPosition(pos++);
        }

        menuRepo.saveAll(current);
        return getMenu(groupId);
    }

    @Transactional
    public GroupServiceMenuDto updateQuotes(Long groupId, UpdateGroupServiceMenuQuotesRequest req) {
        if (req == null || req.quotes() == null || req.quotes().isEmpty()) {
            return getMenu(groupId);
        }
        List<GroupServiceMenuItem> items = menuRepo.findByGroupIdOrderByPositionAsc(groupId);
        Map<Long, GroupServiceMenuItem> byId = new HashMap<>();
        for (GroupServiceMenuItem item : items) {
            byId.put(item.getId(), item);
        }
        for (UpdateGroupServiceMenuQuotesRequest.QuoteEntry entry : req.quotes()) {
            if (entry.menuItemId() == null) continue;
            GroupServiceMenuItem item = byId.get(entry.menuItemId());
            if (item == null) continue;
            item.setQuotedValue(entry.quotedValue());
        }
        menuRepo.saveAll(items);

        return getMenu(groupId);
    }

    @Transactional
    public void deleteMenuItem(Long groupId, Long menuItemId) {
        // Acción sensible: requiere autorización si el usuario NO es ADMIN
        authCodeService.requireAuthorizationIfNotAdmin("ADMIN");

        GroupServiceMenuItem menuItem = menuRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found"));

        ServiceCode code = menuItem.getService().getCode();

        // Borrar datos del servicio del grupo y de cada miembro (replicado)
        switch (code) {
            case FERRY -> {
                groupFerryServiceRepository.deleteByMenuItemId(menuItemId);
                memberFerryServiceRepository.deleteByMenuItemId(menuItemId);
            }
            case TRASLADOS -> {
                groupTransferServiceRepository.deleteByMenuItemId(menuItemId);
                memberTransferServiceRepository.deleteByMenuItemId(menuItemId);
            }
            case TRASLADOS_DESTINO -> {
                groupDestinationTransferServiceRepository.deleteByMenuItemId(menuItemId);
                memberDestinationTransferServiceRepository.deleteByMenuItemId(menuItemId);
            }
            case ALOJAMIENTOS -> {
                // Primero borrar dependencias (habitaciones) para evitar constraint FK.
                groupAccommodationServiceRepository.findByMenuItemId(menuItemId)
                        .ifPresent(acc -> groupAccommodationRoomRepository.deleteByAccommodationService_Id(acc.getId()));
                groupAccommodationServiceRepository.deleteByMenuItemId(menuItemId);
                memberAccommodationServiceRepository.deleteByMenuItemId(menuItemId);
            }
            case AEREOS -> {
                groupAirServiceRepository.deleteByMenuItemId(menuItemId);
                memberAirServiceRepository.deleteByMenuItemId(menuItemId);
            }
            case ADICIONALES -> {
                // Sin tabla propia — el texto libre se guarda en group_service_menu_item.notes
                // No hay datos adicionales que limpiar al eliminar.
            }
            default -> {
                // si se agregan nuevos servicios en el futuro, acá se puede extender
            }
        }

        menuRepo.delete(menuItem);
    }

}
