package app.coincidir.api.service;

import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.GroupAirService;
import app.coincidir.api.domain.GroupFerryService;
import app.coincidir.api.domain.GroupTransferService;
import app.coincidir.api.domain.GroupDestinationTransferService;
import app.coincidir.api.domain.expense.Expense;
import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.operations.*;
import app.coincidir.api.repository.*;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperationsService {

    private final TravelGroupRepository groupRepo;
    private final GroupServiceMenuItemRepository menuItemRepo;
    private final GroupAirServiceRepository groupAirRepo;
    private final GroupFerryServiceRepository groupFerryRepo;
    private final GroupTransferServiceRepository groupTransferRepo;
    private final GroupDestinationTransferServiceRepository groupDestinationTransferRepo;
    private final ExpenseRepository expenseRepo;
    private final GroupOperationsRepository groupOperationsRepo;
    private final ServiceOperationStatusDefinitionRepository statusDefRepo;
    private final ServiceEmittedNotificationService emittedNotificationService;
    private final GroupAccommodationServiceRepository accommodationRepo;
    private final ServicePaymentPlanRepository servicePaymentPlanRepo;
    private final ServicePaymentRecordRepository servicePaymentRecordRepo;
    private final OperationsRoleGuardService operationsRoleGuardService;


    @Transactional
    public GroupOperationsStateDto getState(Long groupId) {
        GroupOperations ops = groupOperationsRepo.findByGroupId(groupId).orElse(null);
        boolean emitted = ops != null && ops.isEmittedComplete();
        Instant emittedAt = ops != null ? ops.getEmittedCompleteAt() : null;

        boolean servicesComplete = ops != null && ops.isServicesComplete();
        Instant servicesCompleteAt = ops != null ? ops.getServicesCompleteAt() : null;

        List<GroupServiceMenuItem> items = menuItemRepo.findByGroupIdOrderByPositionAsc(groupId);

        // normaliza nulls antiguos (y autocalcula servicios con regla automática)
        for (GroupServiceMenuItem it : items) {
            if (it == null) continue;

            ServiceCode sc = it.getService() != null ? it.getService().getCode() : null;
            OperationStatusCode current = it.getOperationStatus();
            OperationStatusCode desired = current;

            // AÉREOS: el estado es MANUAL (solo verde cuando el estado es EMITIDO)
            // Otros servicios pueden auto-calcular su estado.
            if (sc == ServiceCode.FERRY) {
                desired = computeAutoStatus(groupId, it, sc);
            } else if (sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO) {
                // PAGADO es estado final manual — nunca revertirlo automáticamente
                if (current == OperationStatusCode.PAGADO) {
                    desired = OperationStatusCode.PAGADO;
                // SOLICITADO prevalece mientras no haya pagos; con pago → PAGADO
                } else if (current == OperationStatusCode.SOLICITADO) {
                    OperationStatusCode auto = computeAutoStatus(groupId, it, sc);
                    desired = (auto == OperationStatusCode.PENDIENTE) ? OperationStatusCode.SOLICITADO : auto;
                } else {
                    desired = computeAutoStatus(groupId, it, sc);
                }
            } else if (desired == null) {
                desired = OperationStatusCode.PENDIENTE;
            }

            if (desired != current) {
                it.setOperationStatus(desired);
                it.setOperationStatusUpdatedAt(Instant.now());
                try {
                    menuItemRepo.save(it);
                } catch (Exception ignored) {
                }
            }
        }

        // defs solo para los service codes presentes
        Set<ServiceCode> codesPresent = items.stream()
                .map(i -> i.getService() != null ? i.getService().getCode() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, List<ServiceOperationStatusDefinitionDto>> defsByService = new LinkedHashMap<>();
        for (ServiceCode code : codesPresent) {
            List<ServiceOperationStatusDefinitionDto> defs = statusDefRepo
                    .findByServiceCodeAndActiveTrueOrderBySortOrderAsc(code)
                    .stream()
                    .map(ServiceOperationStatusDefinitionDto::fromEntity)
                    .toList();
            defsByService.put(code.name(), defs);
        }

        // armar map para lookup rápido de label/color
        Map<String, Map<String, ServiceOperationStatusDefinition>> defLookup = new HashMap<>();
        for (ServiceCode code : codesPresent) {
            Map<String, ServiceOperationStatusDefinition> m = new HashMap<>();
            for (ServiceOperationStatusDefinition d : statusDefRepo.findByServiceCodeAndActiveTrueOrderBySortOrderAsc(code)) {
                String k = d.getStatusCode() != null ? d.getStatusCode().name() : null;
                if (k != null) m.put(k, d);
            }
            defLookup.put(code.name(), m);
        }

        List<GroupServiceOperationStateDto> services = new ArrayList<>();
        for (GroupServiceMenuItem it : items) {
            ServiceCode sc = it.getService() != null ? it.getService().getCode() : null;
            String serviceCode = sc != null ? sc.name() : null;
            OperationStatusCode st = it.getOperationStatus() != null ? it.getOperationStatus() : OperationStatusCode.PENDIENTE;
            ServiceOperationStatusDefinition d = serviceCode != null ? defLookup
                    .getOrDefault(serviceCode, Map.of())
                    .get(st.name()) : null;

            services.add(GroupServiceOperationStateDto.builder()
                    .menuItemId(it.getId())
                    .groupId(groupId)
                    .serviceCode(serviceCode)
                    .displayName(it.getDisplayName())
                    .position(it.getPosition())
                    .statusCode(st.name())
                    .statusLabel(d != null ? d.getLabel() : st.name())
                    .color(d != null && d.getColor() != null ? d.getColor().name() : null)
                    .statusUpdatedAt(it.getOperationStatusUpdatedAt())
                    .build());
        }

        return GroupOperationsStateDto.builder()
                .groupId(groupId)
                .emittedComplete(emitted)
                .emittedCompleteAt(emittedAt)
                .servicesComplete(servicesComplete)
                .servicesCompleteAt(servicesCompleteAt)
                .services(services)
                .definitionsByService(defsByService)
                .build();
    }

    private void validateAllServicesGreen(Long groupId) {
        List<GroupServiceMenuItem> items = menuItemRepo.findByGroupIdOrderByPositionAsc(groupId);
        if (items.isEmpty()) {
            throw new BadRequestException("No se puede marcar Servicio completo: el grupo no tiene servicios");
        }
        for (GroupServiceMenuItem it : items) {
            ServiceCode sc = it.getService() != null ? it.getService().getCode() : null;
            OperationStatusCode st;
            if (sc == ServiceCode.FERRY || sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO) {
                // FERRY: estado automático según totalCost vs total pagado
                st = computeAutoStatus(groupId, it, sc);
                // Mantener consistencia en DB para futuras validaciones/listados
                try {
                    if (it.getOperationStatus() == null || it.getOperationStatus() != st) {
                        it.setOperationStatus(st);
                        it.setOperationStatusUpdatedAt(Instant.now());
                        menuItemRepo.save(it);
                    }
                } catch (Exception ignored) {
                }
            } else {
                st = it.getOperationStatus() != null ? it.getOperationStatus() : OperationStatusCode.PENDIENTE;
            }
            if (sc == null) throw new BadRequestException("Item de servicio inválido");
            ServiceOperationStatusDefinition d = statusDefRepo.findByServiceCodeAndStatusCode(sc, st)
                    .orElse(null);
            if (d == null || d.getColor() != ServiceStatusColor.GREEN) {
                throw new BadRequestException("No se puede marcar Servicio completo: hay servicios sin estado verde");
            }
        }
    }

    /**
     * Estado automático por servicio:
     * - AEREOS/FERRY: PENDIENTE mientras (totalCost - totalPagado) != 0, EMITIDO cuando restante <= 0
     * - TRASLADOS/TRASLADOS_DESTINO: PENDIENTE hasta que haya pagos y los datos estén completos;
     *   SENADO cuando totalPagado < totalCost; PAGADO cuando totalPagado >= totalCost.
     */
    private OperationStatusCode computeAutoStatus(Long groupId, GroupServiceMenuItem item, ServiceCode sc) {
        if (groupId == null || item == null || item.getId() == null || sc == null) {
            return OperationStatusCode.PENDIENTE;
        }

        BigDecimal totalCost = null;
        boolean transfersDataComplete = true;
        try {
            if (sc == ServiceCode.AEREOS) {
                totalCost = groupAirRepo.findByMenuItemId(item.getId())
                        .map(GroupAirService::getTotalCost)
                        .orElse(null);
            } else if (sc == ServiceCode.FERRY) {
                totalCost = groupFerryRepo.findByMenuItemId(item.getId())
                        .map(GroupFerryService::getTotalCost)
                        .orElse(null);
            } else if (sc == ServiceCode.TRASLADOS) {
                GroupTransferService t = groupTransferRepo.findByMenuItemId(item.getId()).orElse(null);
                if (t != null) {
                    totalCost = t.getTotalCost();
                    transfersDataComplete = isTransfersDataComplete(t.getProvider(), t.getPickupPlace(), t.getDestinationPlace(), t.getReservationCode(), t.getDepartureDate(), t.getDepartureTime(), t.getReturnDate(), t.getReturnTime());
                } else {
                    transfersDataComplete = false;
                }
            } else if (sc == ServiceCode.TRASLADOS_DESTINO) {
                GroupDestinationTransferService t = groupDestinationTransferRepo.findByMenuItemId(item.getId()).orElse(null);
                if (t != null) {
                    totalCost = t.getTotalCost();
                    boolean base = isTransfersDataComplete(t.getProvider(), t.getPickupPlace(), t.getDestinationPlace(), t.getReservationCode(), t.getDepartureDate(), t.getDepartureTime(), t.getReturnDate(), t.getReturnTime());
                    // Para destino, si vienen seteados country/city, exigirlos (si la BD/UX los usa)
                    boolean countryOk = t.getCountry() == null || !t.getCountry().trim().isBlank();
                    boolean cityOk = t.getCity() == null || !t.getCity().trim().isBlank();
                    transfersDataComplete = base && countryOk && cityOk;
                } else {
                    transfersDataComplete = false;
                }
            }
        } catch (Exception ignored) {
        }

        if (totalCost == null || totalCost.compareTo(BigDecimal.ZERO) <= 0) {
            return OperationStatusCode.PENDIENTE;
        }

        BigDecimal paid = sumPaidFromExpenses(groupId, item.getId(), sc);
        if (paid == null) paid = BigDecimal.ZERO;
        BigDecimal remaining = totalCost.subtract(paid);

        if (sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO) {
            // Sin pagos o con datos incompletos: siempre PENDIENTE
            if (!transfersDataComplete || paid.compareTo(BigDecimal.ZERO) <= 0) {
                return OperationStatusCode.PENDIENTE;
            }
            // Con pago registrado: PAGADO (flujo traslados: PENDIENTE → SOLICITADO → PAGADO)
            return OperationStatusCode.PAGADO;
        }

        return (remaining.compareTo(BigDecimal.ZERO) <= 0)
                ? OperationStatusCode.EMITIDO
                : OperationStatusCode.PENDIENTE;
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
        // reservationCode no es obligatorio para activar el auto-status
        if (departureDate == null) return false;
        if (departureTime == null) return false;

        // Si se completó alguno de los campos de vuelta, exigir ambos
        boolean hasReturnDate = returnDate != null;
        boolean hasReturnTime = returnTime != null;
        if (hasReturnDate ^ hasReturnTime) return false;

        return true;
    }

    private BigDecimal sumPaidFromExpenses(Long groupId, Long menuItemId, ServiceCode sc) {
        if (groupId == null || menuItemId == null) return BigDecimal.ZERO;

        // Para TRASLADOS: los pagos se registran como ServicePaymentRecord (no Expense)
        if (sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO) {
            try {
                app.coincidir.api.domain.payment.ServicePaymentPlan plan =
                    servicePaymentPlanRepo.findByMenuItemId(menuItemId).orElse(null);
                if (plan != null && plan.getId() != null) {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (app.coincidir.api.domain.payment.ServicePaymentRecord r :
                            servicePaymentRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId())) {
                        if (r != null && r.getAmount() != null) sum = sum.add(r.getAmount());
                    }
                    return sum;
                }
            } catch (Exception ignored) {
            }
            return BigDecimal.ZERO;
        }

        LinkedHashMap<Long, Expense> uniq = new LinkedHashMap<>();
        try {
            for (Expense e : expenseRepo.findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(groupId, menuItemId)) {
                if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
            }
        } catch (Exception ignored) {
        }

        // Compat: pagos temporales de aéreos creados desde Admin Panel (sin menuItemId)
        if (sc == ServiceCode.AEREOS) {
            try {
                for (Expense e : expenseRepo.findAllByGroupIdAndNotesContaining(groupId, "Pago aéreos temporal")) {
                    if (e != null && e.getId() != null) uniq.putIfAbsent(e.getId(), e);
                }
            } catch (Exception ignored) {
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Expense e : uniq.values()) {
            try {
                BigDecimal a = e.getAmount();
                if (a != null) sum = sum.add(a);
            } catch (Exception ignored) {
            }
        }
        return sum;
    }

    @Transactional
    public GroupServiceOperationStateDto updateServiceStatus(Long groupId, Long menuItemId, OperationStatusCode statusCode, String reservationDueDate) {
        GroupServiceMenuItem item = menuItemRepo.findByIdAndGroupId(menuItemId, groupId)
                .orElseThrow(() -> new NotFoundException("Menu item no encontrado"));

        OperationStatusCode prev = item.getOperationStatus();
        ServiceCode sc = item.getService() != null ? item.getService().getCode() : null;

        if (statusCode == null) statusCode = OperationStatusCode.PENDIENTE;

        // Para FERRY: el estado es siempre automático
        // Para TRASLADOS/TRASLADOS_DESTINO: automático SALVO cuando se solicita explícitamente SOLICITADO o PAGADO
        if (sc == ServiceCode.FERRY) {
            statusCode = computeAutoStatus(groupId, item, sc);
        } else if ((sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO)
                && statusCode != OperationStatusCode.SOLICITADO
                && statusCode != OperationStatusCode.PAGADO) {
            statusCode = computeAutoStatus(groupId, item, sc);
        }

        // ALOJAMIENTOS: restricciones de transición de estado
        if (sc == ServiceCode.ALOJAMIENTOS) {
            OperationStatusCode current = prev != null ? prev : OperationStatusCode.PENDIENTE;

            // Pendiente / Reservado -> puede pasar a cualquier estado
            // Señado -> solo puede pasar a Pagado
            if (current == OperationStatusCode.SENADO
                    && statusCode != OperationStatusCode.SENADO
                    && statusCode != OperationStatusCode.PAGADO) {
                throw new BadRequestException("Desde estado SEÑADO solo puede pasar a PAGADO");
            }

            // Pagado -> no puede pasar a ningún otro estado
            if (current == OperationStatusCode.PAGADO && statusCode != OperationStatusCode.PAGADO) {
                throw new BadRequestException("El estado PAGADO no permite cambios");
            }
        }

        // Para ALOJAMIENTOS: si se marca RESERVADO, se requiere fecha de vencimiento de reserva.
        if (sc == ServiceCode.ALOJAMIENTOS && statusCode == OperationStatusCode.RESERVADO) {
            String due = reservationDueDate != null ? reservationDueDate.trim() : "";
            if (due.isBlank()) {
                throw new BadRequestException("La fecha de vencimiento de reserva es obligatoria");
            }
            LocalDate parsed;
            try {
                parsed = LocalDate.parse(due);
            } catch (Exception ex) {
                throw new BadRequestException("Fecha de vencimiento inválida");
            }

            GroupAccommodationService acc = accommodationRepo.findByMenuItemId(menuItemId).orElse(null);
            if (acc == null) {
                throw new BadRequestException("Primero debe guardar el alojamiento y cargar el Total costo");
            }
            if (acc.getTotalCost() == null || acc.getTotalCost().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("Para estado RESERVADO debe cargar el Total costo del servicio");
            }
            acc.setReservationDueDate(parsed);
            accommodationRepo.save(acc);
        }
        // Para cambiar a SENADO, PAGADO o EMITIDO es obligatorio tener al menos un pago registrado
        // Traslados: el estado EMITIDO es calculado automáticamente cuando hay pago, no se valida aquí
        boolean isAutoEmitTraslados = (sc == ServiceCode.TRASLADOS || sc == ServiceCode.TRASLADOS_DESTINO)
                && statusCode == OperationStatusCode.PAGADO;
        if (sc != ServiceCode.AEREOS && sc != ServiceCode.FERRY && !isAutoEmitTraslados
                && (statusCode == OperationStatusCode.SENADO || statusCode == OperationStatusCode.PAGADO || statusCode == OperationStatusCode.EMITIDO)) {
            boolean hasPayments = false;
            try {
                app.coincidir.api.domain.payment.ServicePaymentPlan p = servicePaymentPlanRepo.findByMenuItemId(menuItemId).orElse(null);
                if (p != null && p.getId() != null) {
                    hasPayments = !servicePaymentRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(p.getId()).isEmpty();
                }
            } catch (Exception ignored) {
            }
            if (!hasPayments) {
                throw new BadRequestException("Para guardar en estado SENADO, PAGADO o EMITIDO debe registrar al menos un pago");
            }
        }
        item.setOperationStatus(statusCode);
        item.setOperationStatusUpdatedAt(Instant.now());
        menuItemRepo.save(item);

        // Notificaciones por mail deshabilitadas (no enviar al cliente al emitir/pagar)
        // if (statusCode == OperationStatusCode.EMITIDO && prev != OperationStatusCode.EMITIDO) { ... }
        // if (statusCode == OperationStatusCode.PAGADO && prev != OperationStatusCode.PAGADO) { ... }

        if (false) {
        }

        ServiceOperationStatusDefinition d = (sc != null)
                ? statusDefRepo.findByServiceCodeAndStatusCode(sc, statusCode).orElse(null)
                : null;

        return GroupServiceOperationStateDto.builder()
                .menuItemId(item.getId())
                .groupId(groupId)
                .serviceCode(sc != null ? sc.name() : null)
                .displayName(item.getDisplayName())
                .position(item.getPosition())
                .statusCode(statusCode.name())
                .statusLabel(d != null ? d.getLabel() : statusCode.name())
                .color(d != null && d.getColor() != null ? d.getColor().name() : null)
                .statusUpdatedAt(item.getOperationStatusUpdatedAt())
                .build();
    }

    @Transactional
    public GroupOperationsStateDto setEmittedComplete(Long groupId, boolean emittedComplete) {
        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado"));

        if (emittedComplete) {
            // Validación: todos los items del menú deben estar en color GREEN
            // (mismo criterio de "Servicio completo")
            validateAllServicesGreen(groupId);
        }

        GroupOperations ops = groupOperationsRepo.findByGroupId(groupId)
                .orElseGet(() -> GroupOperations.builder().group(group).build());
        ops.setEmittedComplete(emittedComplete);
        if (emittedComplete) {
            ops.setEmittedCompleteAt(Instant.now());
        } else {
            ops.setEmittedCompleteAt(null);
        }
        groupOperationsRepo.save(ops);

        return getState(groupId);
    }

    @Transactional
    public GroupOperationsStateDto setServicesComplete(Long groupId, boolean servicesComplete) {
        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado"));

        if (servicesComplete) {
            validateAllServicesGreen(groupId);
        }

        GroupOperations ops = groupOperationsRepo.findByGroupId(groupId)
                .orElseGet(() -> GroupOperations.builder().group(group).build());

        ops.setServicesComplete(servicesComplete);
        if (servicesComplete) {
            ops.setServicesCompleteAt(Instant.now());
        } else {
            ops.setServicesCompleteAt(null);
        }
        groupOperationsRepo.save(ops);

        return getState(groupId);
    }

    @Transactional(readOnly = true)
    public Map<String, List<ServiceOperationStatusDefinitionDto>> listStatusDefinitions(Set<ServiceCode> filter) {
        List<ServiceOperationStatusDefinition> defs;
        if (filter == null || filter.isEmpty()) {
            defs = statusDefRepo.findByActiveTrueOrderByServiceCodeAscSortOrderAsc();
        } else {
            defs = filter.stream()
                    .flatMap(c -> statusDefRepo.findByServiceCodeAndActiveTrueOrderBySortOrderAsc(c).stream())
                    .toList();
        }
        Map<String, List<ServiceOperationStatusDefinitionDto>> out = new LinkedHashMap<>();
        for (ServiceOperationStatusDefinition d : defs) {
            String key = d.getServiceCode() != null ? d.getServiceCode().name() : "";
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(ServiceOperationStatusDefinitionDto.fromEntity(d));
        }
        // ordenar por sortOrder dentro de cada lista (por seguridad)
        out.values().forEach(list -> list.sort(Comparator.comparing(ServiceOperationStatusDefinitionDto::getSortOrder, Comparator.nullsLast(Integer::compareTo))));
        return out;
    }
}
