package app.coincidir.api.web;

import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.service.OperationsService;
import app.coincidir.api.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationsController {

    private final OperationsService operationsService;
    private final JdbcTemplate jdbc;

    @GetMapping("/groups/{groupId}/state")
    public GroupOperationsStateDto getState(@PathVariable Long groupId) {
        return operationsService.getState(groupId);
    }

    @PutMapping("/groups/{groupId}/services/{menuItemId}/status")
    public GroupServiceOperationStateDto updateStatus(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpdateServiceOperationStatusRequest body
    ) {
        OperationStatusCode code;
        try {
            code = OperationStatusCode.valueOf(body.statusCode().trim().toUpperCase());
        } catch (Exception ex) {
            throw new BadRequestException("Estado inválido: " + body.statusCode());
        }
        return operationsService.updateServiceStatus(groupId, menuItemId, code, body.reservationDueDate());
    }

    @PutMapping("/groups/{groupId}/emitted-complete")
    public GroupOperationsStateDto setEmittedComplete(
            @PathVariable Long groupId,
            @RequestBody SetGroupEmittedCompleteRequest body
    ) {
        return operationsService.setEmittedComplete(groupId, body.emittedComplete());
    }

    @PutMapping("/groups/{groupId}/services-complete")
    public GroupOperationsStateDto setServicesComplete(
            @PathVariable Long groupId,
            @RequestBody SetGroupServicesCompleteRequest body
    ) {
        return operationsService.setServicesComplete(groupId, body.servicesComplete());
    }

    @GetMapping("/status-definitions")
    public Map<String, java.util.List<ServiceOperationStatusDefinitionDto>> listDefinitions(
            @RequestParam(name = "serviceCodes", required = false) Set<String> serviceCodes
    ) {
        Set<ServiceCode> codes = null;
        if (serviceCodes != null && !serviceCodes.isEmpty()) {
            try {
                codes = serviceCodes.stream().map(s -> ServiceCode.valueOf(s.trim().toUpperCase()))
                        .collect(java.util.stream.Collectors.toSet());
            } catch (Exception ex) {
                throw new BadRequestException("serviceCodes inválido");
            }
        }
        return operationsService.listStatusDefinitions(codes);
    }

    /**
     * Busca grupos que tengan un registro de pago aéreo con el nro de vuelo y fecha de ida indicados.
     * Retorna una lista de groupIds que coinciden.
     */
    @GetMapping("/search/flight")
    public List<Long> searchByFlight(
            @RequestParam("flightNumber") String flightNumber,
            @RequestParam(value = "departureDate", required = false) String departureDate
    ) {
        final String fn = flightNumber == null ? "" : flightNumber.trim().toUpperCase().replaceAll("\\s+", "");
        if (fn.isBlank()) return List.of();

        String sql;
        Object[] params;

        if (departureDate != null && !departureDate.isBlank()) {
            sql = "SELECT DISTINCT gsmi.group_id " +
                  "FROM service_payment_record spr " +
                  "JOIN service_payment_plan spp ON spp.id = spr.plan_id " +
                  "JOIN group_service_menu_item gsmi ON gsmi.id = spp.menu_item_id " +
                  "LEFT JOIN group_air_service gas ON gas.menu_item_id = gsmi.id " +
                  "WHERE UPPER(REPLACE(spr.flight_number, ' ', '')) = ? " +
                  "AND (gas.departure_date = ? OR gas.departure_date IS NULL) " +
                  "ORDER BY gsmi.group_id";
            params = new Object[]{ fn, departureDate.trim() };
        } else {
            sql = "SELECT DISTINCT gsmi.group_id " +
                  "FROM service_payment_record spr " +
                  "JOIN service_payment_plan spp ON spp.id = spr.plan_id " +
                  "JOIN group_service_menu_item gsmi ON gsmi.id = spp.menu_item_id " +
                  "WHERE UPPER(REPLACE(spr.flight_number, ' ', '')) = ? " +
                  "ORDER BY gsmi.group_id";
            params = new Object[]{ fn };
        }

        try {
            return jdbc.queryForList(sql, Long.class, params);
        } catch (Exception e) {
            return List.of();
        }
    }
}
