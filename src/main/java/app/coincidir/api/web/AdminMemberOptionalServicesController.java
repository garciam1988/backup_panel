package app.coincidir.api.web;

import app.coincidir.api.service.MemberOptionalServicesService;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/members/{memberId}")
@RequiredArgsConstructor
public class AdminMemberOptionalServicesController {

    private final MemberOptionalServicesService optionalService;

    // --------- MENU OPCIONAL (por miembro) ---------

    @GetMapping("/optional-service-menu")
    public ResponseEntity<MemberOptionalServiceMenuDto> getOptionalMenu(
            @PathVariable Long groupId,
            @PathVariable Long memberId
    ) {
        return ResponseEntity.ok(optionalService.getOptionalMenu(groupId, memberId));
    }

    @PostMapping("/optional-service-menu/items")
    public ResponseEntity<MemberOptionalServiceMenuItemDto> addOptionalMenuItem(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @RequestBody CreateMemberOptionalServiceMenuItemRequest body
    ) {
        return ResponseEntity.ok(optionalService.addOptionalMenuItem(groupId, memberId, body));
    }

    @DeleteMapping("/optional-service-menu/items/{itemId}")
    public ResponseEntity<Void> deleteOptionalMenuItem(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId
    ) {
        optionalService.deleteOptionalMenuItem(groupId, memberId, itemId);
        return ResponseEntity.noContent().build();
    }

    // --------- EXCURSIONES ---------

    @GetMapping("/optional-service-items/{itemId}/excursions")
    public ResponseEntity<MemberOptionalExcursionDto> getExcursions(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId
    ) {
        MemberOptionalExcursionDto dto = optionalService.getExcursions(groupId, memberId, itemId);
        if (dto == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/optional-service-items/{itemId}/excursions")
    public ResponseEntity<MemberOptionalExcursionDto> upsertExcursions(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @RequestBody MemberOptionalExcursionDto body
    ) {
        return ResponseEntity.ok(optionalService.upsertExcursions(groupId, memberId, itemId, body));
    }

    // --------- ASISTENCIA AL VIAJERO ---------

    @GetMapping("/optional-service-items/{itemId}/travel-assistance")
    public ResponseEntity<MemberOptionalTravelAssistanceDto> getTravelAssistance(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId
    ) {
        MemberOptionalTravelAssistanceDto dto = optionalService.getTravelAssistance(groupId, memberId, itemId);
        if (dto == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/optional-service-items/{itemId}/travel-assistance")
    public ResponseEntity<MemberOptionalTravelAssistanceDto> upsertTravelAssistance(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @RequestBody MemberOptionalTravelAssistanceDto body
    ) {
        return ResponseEntity.ok(optionalService.upsertTravelAssistance(groupId, memberId, itemId, body));
    }

    // --------- EQUIPAJE ---------

    @GetMapping("/optional-service-items/{itemId}/luggage")
    public ResponseEntity<MemberOptionalLuggageDto> getLuggage(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId
    ) {
        MemberOptionalLuggageDto dto = optionalService.getLuggage(groupId, memberId, itemId);
        if (dto == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/optional-service-items/{itemId}/luggage")
    public ResponseEntity<MemberOptionalLuggageDto> upsertLuggage(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @RequestBody MemberOptionalLuggageDto body
    ) {
        return ResponseEntity.ok(optionalService.upsertLuggage(groupId, memberId, itemId, body));
    }
}
