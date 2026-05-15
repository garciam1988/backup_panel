package app.coincidir.api.web.user;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.user.dto.UserMatchDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.security.Principal;

@RestController
@RequestMapping("/api/user/match")
public class UserMatchController {

    private final TravelRequestRepository requestRepo;

    public UserMatchController(TravelRequestRepository requestRepo) {
        this.requestRepo = requestRepo;
    }

    @GetMapping("/me")
    public ResponseEntity<UserMatchDto> me(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }

        String email = principal.getName();
        TravelRequest tr = requestRepo.findTopByEmailIgnoreCaseOrderByIdDesc(email).orElse(null);
        if (tr == null) {
            return ResponseEntity.notFound().build();
        }

        UserMatchDto dto = new UserMatchDto();
        dto.requestId = tr.getId();
        dto.email = email;
        dto.destination = tr.getDestination();
        dto.whenLabel = tr.getWhenLabel();
        dto.pax = tr.getTravelersTotal();
        dto.companionPreference = tr.getCompanionPreference();
	    dto.sharedRoom = tr.getSharedRoom();
        dto.luggageCount = tr.getLuggageCount();
        dto.travelAssistance = tr.getTravelAssistance();
        dto.includesTours = tr.getIncludesTours();
	    dto.datePresetId = tr.getDatePresetId() == null ? null : String.valueOf(tr.getDatePresetId());
	    // tripType/month/year/notes are optional in the User Panel UI; they are kept null when not available.

        // Extra preferences - optional, safe for future UI usage
        Map<String, Object> prefs = new LinkedHashMap<>();
	    putIfNotNull(prefs, "companionPreference", tr.getCompanionPreference());
	    putIfNotNull(prefs, "sharedRoom", tr.getSharedRoom());
	    putIfNotNull(prefs, "luggageCount", tr.getLuggageCount());
	    putIfNotNull(prefs, "travelAssistance", tr.getTravelAssistance());
	    putIfNotNull(prefs, "includesTours", tr.getIncludesTours());
	    putIfNotNull(prefs, "travelersTotal", tr.getTravelersTotal());
	    putIfNotNull(prefs, "datePresetId", tr.getDatePresetId());
        dto.preferences = prefs.isEmpty() ? null : prefs;

        return ResponseEntity.ok(dto);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
