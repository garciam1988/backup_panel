package app.coincidir.api.web.user;

import app.coincidir.api.service.UserTripsService;
import app.coincidir.api.web.user.dto.UserTripsMeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserTripsController {

    private final UserTripsService userTripsService;

    /**
     * Devuelve TODAS las solicitudes del usuario autenticado (múltiples en curso + finalizadas).
     */
    @GetMapping("/trips/me")
    public ResponseEntity<UserTripsMeDto> myTrips(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.ok(UserTripsMeDto.builder().build());
        }
        String email = principal.getName().trim();
        return ResponseEntity.ok(userTripsService.listTripsForUser(email));
    }

    /**
     * Anula una solicitud (hard delete) para que el motor no la tenga en cuenta.
     */
    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<Void> cancel(@PathVariable Long requestId, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }
        userTripsService.cancelRequest(principal.getName().trim(), requestId);
        return ResponseEntity.noContent().build();
    }

    // Aliases de compatibilidad con variantes usadas por distintos frontends
    @DeleteMapping("/trips/{requestId}")
    public ResponseEntity<Void> cancelAliasTrips(@PathVariable Long requestId, Principal principal) {
        return cancel(requestId, principal);
    }

    @DeleteMapping("/requests/me/{requestId}")
    public ResponseEntity<Void> cancelAliasMe(@PathVariable Long requestId, Principal principal) {
        return cancel(requestId, principal);
    }
}
