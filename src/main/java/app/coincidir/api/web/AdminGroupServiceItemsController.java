package app.coincidir.api.web;

import app.coincidir.api.service.GroupServiceItemsService;
import app.coincidir.api.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/service-items/{menuItemId}")
@RequiredArgsConstructor
public class AdminGroupServiceItemsController {

    private final GroupServiceItemsService itemsService;

    // ---------- FERRY ----------
    @GetMapping("/ferry")
    public ResponseEntity<GroupFerryServiceDto> getFerry(@PathVariable Long groupId, @PathVariable Long menuItemId) {
        return ResponseEntity.ok(itemsService.getFerry(groupId, menuItemId));
    }

    @PutMapping("/ferry")
    public ResponseEntity<GroupFerryServiceDto> upsertFerry(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody UpsertGroupFerryRequest body
    ) {
        return ResponseEntity.ok(itemsService.upsertFerry(groupId, menuItemId, body));
    }

    // ---------- TRASLADOS BA ----------
    @GetMapping("/transfers")
    public GroupTransferServiceDto getTransfers(@PathVariable Long groupId, @PathVariable Long menuItemId) {
        return itemsService.getTransfers(groupId, menuItemId);
    }

    @PutMapping("/transfers")
    public GroupTransferServiceDto upsertTransfers(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupTransferRequest body
    ) {
        return itemsService.upsertTransfers(groupId, menuItemId, body);
    }

    // ---------- TRASLADOS DESTINO ----------
    @GetMapping("/destination-transfers")
    public GroupDestinationTransferServiceDto getDestinationTransfers(@PathVariable Long groupId, @PathVariable Long menuItemId) {
        return itemsService.getDestinationTransfers(groupId, menuItemId);
    }

    @PutMapping("/destination-transfers")
    public GroupDestinationTransferServiceDto upsertDestinationTransfers(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupTransferRequest body
    ) {
        return itemsService.upsertDestinationTransfers(groupId, menuItemId, body);
    }

    // ---------- ALOJAMIENTOS ----------
    @GetMapping("/accommodations")
    public GroupAccommodationServiceDto getAccommodation(@PathVariable Long groupId, @PathVariable Long menuItemId) {
        return itemsService.getAccommodation(groupId, menuItemId);
    }

    @PutMapping("/accommodations")
    public GroupAccommodationServiceDto upsertAccommodation(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupAccommodationRequest body
    ) {
        return itemsService.upsertAccommodation(groupId, menuItemId, body);
    }

    @PutMapping("/accommodations/rooms")
    public GroupAccommodationServiceDto upsertAccommodationRooms(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody UpsertAccommodationRoomsRequest body
    ) {
        return itemsService.upsertAccommodationRooms(groupId, menuItemId, body);
    }

    // ---------- AEREOS ----------
    @GetMapping("/air-services")
    public ResponseEntity<AirServiceDto> getAir(@PathVariable Long groupId, @PathVariable Long menuItemId) {
        AirServiceDto dto = itemsService.getAir(groupId, menuItemId);
        if (dto == null) {
            AirServiceDto empty = new AirServiceDto();
            empty.setGroupId(groupId);
            return ResponseEntity.ok(empty);
        }
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/air-services")
    public ResponseEntity<AirServiceDto> upsertAir(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody AirServiceDto body
    ) {
        return ResponseEntity.ok(itemsService.upsertAir(groupId, menuItemId, body));
    }

    // ---------- ADICIONALES ----------
    @GetMapping("/adicionales")
    public ResponseEntity<java.util.Map<String, Object>> getAdicionales(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId
    ) {
        return ResponseEntity.ok(itemsService.getAdicionales(groupId, menuItemId));
    }

    @PutMapping("/adicionales")
    public ResponseEntity<java.util.Map<String, Object>> upsertAdicionales(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody java.util.Map<String, Object> body
    ) {
        return ResponseEntity.ok(itemsService.upsertAdicionales(groupId, menuItemId, body));
    }
}
