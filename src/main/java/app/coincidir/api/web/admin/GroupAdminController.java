// app/coincidir/api/admin/GroupAdminController.java
package app.coincidir.api.web.admin;

import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.web.admin.dto.GroupSummaryDto;
import app.coincidir.api.web.admin.dto.UpdateStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import app.coincidir.api.web.admin.dto.UpdateGroupDatesRequest;
import app.coincidir.api.web.admin.dto.UpdateAutoSearchRequest;
import app.coincidir.api.web.admin.dto.UpdateOperationConfirmedRequest;
import app.coincidir.api.web.admin.dto.CandidateSummaryDto;
import app.coincidir.api.web.admin.dto.AddMembersRequest;
import app.coincidir.api.web.admin.dto.AddManualMemberRequest;

import java.util.List;
import java.security.Principal;

@RestController
@RequestMapping("/api/admin/groups")
@RequiredArgsConstructor
public class GroupAdminController {

    private final GroupAdminService service;

    @GetMapping
    public List<GroupSummaryDto> list(
            @RequestParam(required = false) GroupStatus status,
            @RequestParam(name = "paymentsUnverified", required = false) Boolean paymentsUnverified,
            @RequestParam(required = false) String q
    ) {
        return service.listGroups(status, paymentsUnverified, q);
    }


    @PostMapping("/{id}/assign-to-me")
    public GroupSummaryDto assignToMe(@PathVariable Long id, Principal principal) {
        return service.assignToMe(id, principal != null ? principal.getName() : null);
    }

    @PutMapping("/{id}/dates")
    public GroupSummaryDto updateDates(@PathVariable Long id, @RequestBody UpdateGroupDatesRequest body) {
        return service.updateDates(id, body);
    }

    @PostMapping("/{id}/disband")
    public ResponseEntity<?> disband(@PathVariable Long id) {
        int changed = service.disband(id);
        return ResponseEntity.ok().body(java.util.Map.of("changed", changed));
    }

    @GetMapping("/{id}/candidates")
    public List<CandidateSummaryDto> listCandidatesForGroup(
            @PathVariable Long id,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String companionPreference,
            @RequestParam(required = false) String whenLabel,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) Boolean ignoreTravelDates
    ) {
        return service.findCandidatesForGroup(
                id, q, email, phone, gender,
                companionPreference, whenLabel, destination,
                ignoreTravelDates
        );
    }

    @PostMapping("/{id}/add-members")
    public GroupSummaryDto addMembers(
            @PathVariable Long id,
            @RequestBody AddMembersRequest body
    ) {
        return service.addMembers(id, body.requestIds());
    }

    @PostMapping("/generate")
    public GroupSummaryDto generateGroup(
            @RequestBody app.coincidir.api.web.admin.dto.GenerateGroupFromRequestsRequest body,
            Principal principal
    ) {
        return service.generateGroupFromRequests(
                body.requestIds(),
                body.seedRequestId(),
                body.travelDate(),
                body.paymentTitularMemberId(),
                principal != null ? principal.getName() : null,
                body.forcedId()
        );
    }

    @GetMapping("/config/forzar-nro-operacion")
    public java.util.Map<String, Boolean> getForzarNroOperacionConfig() {
        boolean active = service.isForzarNroOperacionActive();
        return java.util.Map.of("active", active);
    }

    @GetMapping("/exists/{id}")
    public java.util.Map<String, Boolean> groupExists(@PathVariable Long id) {
        boolean exists = service.groupExistsById(id);
        return java.util.Map.of("exists", exists);
    }

    /**
     * Carga manual: crea un usuario + TravelRequest y lo asocia directamente al grupo.
     */
    @PostMapping("/{id}/add-member-manual")
    public GroupSummaryDto addManualMember(
            @PathVariable Long id,
            @RequestBody AddManualMemberRequest body
    ) {
        return service.addManualMember(id, body);
    }

    @PutMapping("/{id}/auto-search")
    public GroupSummaryDto updateAutoSearch(
            @PathVariable Long id,
            @RequestBody UpdateAutoSearchRequest body
    ) {
        return service.updateAutoSearch(id, body.autoSearchEnabled());
    }


    @PutMapping("/{id}/operation-confirmed")
    public GroupSummaryDto updateOperationConfirmed(
            @PathVariable Long id,
            @RequestBody UpdateOperationConfirmedRequest body
    ) {
        return service.updateOperationConfirmed(id, body.confirmed());
    }


    @PostMapping("/{id}/remove-member/{requestId}")
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long requestId) {
        int changed = service.removeMember(id, requestId);
        return ResponseEntity.ok().body(java.util.Map.of("changed", changed));
    }

    @PutMapping("/{id}/status")
    public GroupSummaryDto updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest body) {
        return service.updateStatus(id, body.status());
    }
}
