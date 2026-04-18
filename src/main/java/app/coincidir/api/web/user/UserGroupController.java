// app/coincidir/api/web/user/UserGroupController.java
package app.coincidir.api.web.user;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.RequestStatus;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.user.dto.SuggestedGroupDto;
import app.coincidir.api.web.user.dto.UserGroupInfoDto;
import app.coincidir.api.web.user.dto.UserGroupMemberDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user/group")
@RequiredArgsConstructor
public class UserGroupController {

    private final TravelRequestRepository requestRepo;
    private final TravelGroupRepository groupRepo;

    @GetMapping("/me")
    public ResponseEntity<UserGroupInfoDto> myGroup(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            // No auth: el frontend maneja estado "sin grupo"
            return ResponseEntity.ok(UserGroupInfoDto.empty());
        }

        // principal.getName() = email del usuario autenticado
        String email = principal.getName().trim();

        // 1) Todas las solicitudes de ese email
        List<TravelRequest> requests = requestRepo.findByEmailIgnoreCase(email);
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.ok(UserGroupInfoDto.empty());
        }

        // 2) La solicitud más reciente que ya tiene grupo asignado
        TravelRequest myRequest = requests.stream()
                .filter(r -> r.getGroup() != null)
                .sorted(Comparator.comparing(TravelRequest::getCreatedAt).reversed())
                .findFirst()
                .orElse(null);

        if (myRequest == null) {
            return ResponseEntity.ok(UserGroupInfoDto.empty());
        }

        TravelGroup group = myRequest.getGroup();

        // 3) Mapear compañeros (evita depender del lazy load de group.getMembers())
        List<TravelRequest> groupMembers = requestRepo.findByGroupIdOrderByIdAsc(group.getId());
        List<UserGroupMemberDto> members = groupMembers.stream()
                .filter(Objects::nonNull)
                .map(tr -> UserGroupMemberDto.builder()
                        .requestId(tr.getId())
                        .name(tr.getName())
                        .gender(tr.getGender())
                        .age(calcAge(tr.getBirthDate()))
                        .build())
                .collect(Collectors.toList());

        UserGroupInfoDto dto = UserGroupInfoDto.builder()
                .groupId(group.getId())
                .destination(myRequest.getDestination())
                .whenLabel(myRequest.getWhenLabel())
                .status(group.getStatus() != null ? group.getStatus().name() : null)
                .memberCount((int) requestRepo.countByGroupId(group.getId()))
                .members(members)
                .build();

        return ResponseEntity.ok(dto);
    }

    // ---------------------------------------------------------------------
    // Compat: usado por UserGroupsAliasController (endpoints legacy /api/groups/*)
    // ---------------------------------------------------------------------

    /**
     * Devuelve grupos sugeridos en base a la última TravelRequest del usuario.
     * Mantiene compatibilidad con controladores alias.
     */
    public ResponseEntity<List<SuggestedGroupDto>> suggested(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }

        // IMPORTANTE: no normalizar a lower-case acá; algunos esquemas/ collations pueden ser case-sensitive.
        String email = principal.getName().trim();

        TravelRequest tr = requestRepo.findTopByEmailIgnoreCaseOrderByIdDesc(email).orElse(null);
        if (tr == null) {
            return ResponseEntity.ok(List.of());
        }

        String dest = normalize(tr.getDestination());
        String when = normalize(tr.getWhenLabel());
        if (dest == null || when == null) {
            return ResponseEntity.ok(List.of());
        }

        String pref = normalize(tr.getCompanionPreference());
        String ageBucket = normalize(ageBucketForRequest(tr));

        // Estados visibles/operables para sugerencias (evita quedarse sin sugerencias por un default distinto)
        List<GroupStatus> statuses = List.of(GroupStatus.OPEN, GroupStatus.NEGOTIATION, GroupStatus.FORMED, GroupStatus.NEW);

        // 1) Estricto: destino + when + pref + edad + status
        List<TravelGroup> groups = groupRepo.findSuggested(dest, when, pref, ageBucket, statuses);

        // 2) Fallback: sin pref/edad
        if ((groups == null || groups.isEmpty()) && (pref != null || ageBucket != null)) {
            groups = groupRepo.findSuggestedLoose(dest, when, statuses);
        }

        // 3) Último fallback: solo destino
        if (groups == null || groups.isEmpty()) {
            groups = groupRepo.findSuggestedByDestination(dest, statuses);
        }

        List<SuggestedGroupDto> out = groups.stream().filter(Objects::nonNull).map(g -> {
            int members = (int) requestRepo.countByGroupId(g.getId());
            return SuggestedGroupDto.builder()
                    .groupId(g.getId())
                    .destination(g.getDestination())
                    .whenLabel(g.getWhenLabel())
                    .status(g.getStatus() != null ? g.getStatus().name() : null)
                    .memberCount(members)
                    .commonPrefs(g.getCommonPrefs())
                    .build();
        }).collect(Collectors.toList());

        return ResponseEntity.ok(out);
    }

    /**
     * Aplica el usuario autenticado a un grupo.
     * Adjunta su última solicitud sin grupo al groupId indicado.
     */
    public ResponseEntity<Void> apply(Long groupId, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }
        if (groupId == null) {
            return ResponseEntity.badRequest().build();
        }

        String email = principal.getName().trim();
        TravelGroup group = groupRepo.findById(groupId).orElse(null);
        if (group == null) {
            return ResponseEntity.notFound().build();
        }

        Optional<TravelRequest> opt = requestRepo.findTopByEmailIgnoreCaseAndGroupIsNullOrderByIdDesc(email);
        TravelRequest tr = opt.orElse(null);
        if (tr == null) {
            return ResponseEntity.notFound().build();
        }

        // Si está agrupado (u otro estado no aplicable), devolvemos conflicto
        if (tr.getGroup() != null) {
            return ResponseEntity.status(409).build();
        }

        tr.setGroup(group);
        tr.setStatus(RequestStatus.GROUPED);
        requestRepo.save(tr);

        return ResponseEntity.ok().build();
    }

    private Integer calcAge(LocalDate birthDate) {
        if (birthDate == null) return null;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    private String ageBucketForRequest(TravelRequest r) {
        if (r == null) return null;
        Integer min = r.getAgeMin();
        Integer max = r.getAgeMax();
        if (min != null && max != null) {
            return min + "-" + max;
        }

        int a = (min == null) ? 18 : min;
        int b = (max == null) ? a : max;

        int start = (a / 5) * 5;
        int end = (b / 5) * 5 + (b % 5 == 0 ? 0 : 4);
        return start + "-" + end;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
