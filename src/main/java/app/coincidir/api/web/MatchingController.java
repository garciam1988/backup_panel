// app/coincidir/api/web/MatchingController.java
package app.coincidir.api.web;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.service.MatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
public class MatchingController {

    private final MatchingService matchingService;
    private final TravelGroupRepository groupRepo;
    private final TravelRequestRepository requestRepo;

    /** Corre el matching ahora (útil para pruebas) */
    @PostMapping("/run")
    public ResponseEntity<?> runNow() {
        int created = matchingService.runOnce();
        return ResponseEntity.ok(Map.of("groupsCreated", created));
    }

    /** Lista grupos formados */
    @GetMapping("/groups")
    public ResponseEntity<?> groups() {
        return ResponseEntity.ok(groupRepo.findAll());
    }

    /** Detalle de un grupo + sus miembros */
    @GetMapping("/groups/{id}")
    public ResponseEntity<?> group(@PathVariable Long id) {
        TravelGroup g = groupRepo.findById(id).orElseThrow();
        var members = requestRepo.findByGroupId(id);
        return ResponseEntity.ok(Map.of("group", g, "members", members));
    }
}
