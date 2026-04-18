// src/main/java/app/coincidir/api/web/AdminMemberServiceItemsController.java
package app.coincidir.api.web;

import app.coincidir.api.service.MemberServiceItemsService;
import app.coincidir.api.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/members/{memberId}/service-items/{menuItemId}")
@RequiredArgsConstructor
public class AdminMemberServiceItemsController {

    private final MemberServiceItemsService memberItemsService;

    // ---------- FERRY ----------
    @GetMapping("/ferry")
    public ResponseEntity<GroupFerryServiceDto> getFerry(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId
    ) {
        return ResponseEntity.ok(memberItemsService.getFerry(groupId, memberId, menuItemId));
    }

    @PutMapping("/ferry")
    public ResponseEntity<GroupFerryServiceDto> upsertFerry(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId,
            @RequestBody UpsertGroupFerryRequest body
    ) {
        return ResponseEntity.ok(memberItemsService.upsertFerry(groupId, memberId, menuItemId, body));
    }

    // ---------- TRASLADOS BA ----------
    @GetMapping("/transfers")
    public GroupTransferServiceDto getTransfers(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId
    ) {
        return memberItemsService.getTransfers(groupId, memberId, menuItemId);
    }

    @PutMapping("/transfers")
    public GroupTransferServiceDto upsertTransfers(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupTransferRequest body
    ) {
        return memberItemsService.upsertTransfers(groupId, memberId, menuItemId, body);
    }

    // ---------- TRASLADOS DESTINO ----------
    @GetMapping("/destination-transfers")
    public GroupDestinationTransferServiceDto getDestinationTransfers(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId
    ) {
        return memberItemsService.getDestinationTransfers(groupId, memberId, menuItemId);
    }

    @PutMapping("/destination-transfers")
    public GroupDestinationTransferServiceDto upsertDestinationTransfers(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupTransferRequest body
    ) {
        return memberItemsService.upsertDestinationTransfers(groupId, memberId, menuItemId, body);
    }

    // ---------- ALOJAMIENTOS ----------
    @GetMapping("/accommodations")
    public GroupAccommodationServiceDto getAccommodation(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId
    ) {
        return memberItemsService.getAccommodation(groupId, memberId, menuItemId);
    }

    @PutMapping("/accommodations")
    public GroupAccommodationServiceDto upsertAccommodation(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertGroupAccommodationRequest body
    ) {
        return memberItemsService.upsertAccommodation(groupId, memberId, menuItemId, body);
    }

    // ---------- AEREOS ----------
    @GetMapping("/air-services")
    public ResponseEntity<AirServiceDto> getAir(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long menuItemId
    ) {
        AirServiceDto dto = memberItemsService.getAir(groupId, memberId, menuItemId);
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
            @PathVariable Long memberId,
            @PathVariable Long menuItemId,
            @RequestBody AirServiceDto body
    ) {
        return ResponseEntity.ok(memberItemsService.upsertAir(groupId, memberId, menuItemId, body));
    }
}
