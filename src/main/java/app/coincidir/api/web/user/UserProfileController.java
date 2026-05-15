package app.coincidir.api.web.user;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.repository.UserAccountRepository;
import app.coincidir.api.web.user.dto.UserProfileDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/user/profile")
public class UserProfileController {

    private final TravelRequestRepository requestRepo;
    private final UserAccountRepository userRepo;
    private final ObjectMapper objectMapper;

    public UserProfileController(
            TravelRequestRepository requestRepo,
            UserAccountRepository userRepo,
            ObjectMapper objectMapper
    ) {
        this.requestRepo = requestRepo;
        this.userRepo = userRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Perfil "full" para el User Panel.
     * Devuelve un objeto libre con claves conocidas (firstName, lastName, phone, etc.)
     * + cualquier dato extra almacenado en user_account.profile_json.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getProfile(Principal principal) {
        String email = (principal == null) ? null : principal.getName();
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        Optional<UserAccount> userOpt = userRepo.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            // Si por algún motivo no existe el user_account, devolvemos mínimo.
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("email", email);
            return ResponseEntity.ok(fallback);
        }

        UserAccount user = userOpt.get();

        // Base
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", user.getEmail());
        if (user.getFirstName() != null) out.put("firstName", user.getFirstName());
        if (user.getLastName() != null) out.put("lastName", user.getLastName());

        // Merge de profile_json (tiene prioridad sobre inferencias)
        Map<String, Object> stored = parseProfileJson(user.getProfileJson());
        for (Map.Entry<String, Object> e : stored.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }

        // Completar faltantes desde la última solicitud (travel_request)
        requestRepo.findTopByEmailIgnoreCaseOrderByIdDesc(email).ifPresent(latest -> {
            // Nombre/Apellido: si no existen aún
            if (!out.containsKey("firstName") && !out.containsKey("lastName")) {
                String fullName = safe(latest.getName());
                if (fullName != null) {
                    String[] parts = fullName.trim().split("\\s+", 2);
                    if (parts.length > 0 && !parts[0].isBlank()) out.put("firstName", parts[0]);
                    if (parts.length > 1 && !parts[1].isBlank()) out.put("lastName", parts[1]);
                }
            }

            putIfAbsentNonBlank(out, "phone", latest.getPhone());
            // city: prioriza city, luego locality
            if (!out.containsKey("city")) {
                String city = safe(latest.getCity());
                if (city == null) city = safe(latest.getLocality());
                if (city != null) out.put("city", city);
            }

            if (!out.containsKey("birthDate")) {
                LocalDate bd = latest.getBirthDate();
                if (bd != null) out.put("birthDate", bd.toString()); // yyyy-MM-dd
            }

            // Preferencias (mapeos simples)
            if (!out.containsKey("smoker") && latest.getSmokeFree() != null) {
                // smokeFree=true => no fumador
                out.put("smoker", !Boolean.TRUE.equals(latest.getSmokeFree()));
            }

            if (!out.containsKey("roomSharing") && latest.getSharedRoom() != null) {
                out.put("roomSharing", Boolean.TRUE.equals(latest.getSharedRoom()) ? "SHARED" : "PRIVATE");
            }

            if (!out.containsKey("luggage") && latest.getLuggageCount() != null) {
                int c = Optional.ofNullable(latest.getLuggageCount()).orElse(0);
                String lvl = (c <= 1) ? "LIGHT" : (c == 2 ? "MEDIUM" : "HEAVY");
                out.put("luggage", lvl);
            }

            // Country default si nunca se guardó
            if (!out.containsKey("country")) {
                out.put("country", "Argentina");
            }
        });

        // Si el usuario tenía first/last en travel_request pero no en user_account, lo reflejamos en salida.
        return ResponseEntity.ok(out);
    }

    /**
     * Guardado de perfil.
     * - firstName/lastName se guardan en columnas.
     * - el resto se guarda en profile_json.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateProfile(
            Principal principal,
            @RequestBody Map<String, Object> payload
    ) {
        String email = (principal == null) ? null : principal.getName();
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        UserAccount user = userRepo.findByEmailIgnoreCase(email)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).build();
        }

        Map<String, Object> safePayload = (payload == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);

        // No permitir cambiar email/role/password por este endpoint
        safePayload.remove("password");
        safePayload.remove("role");
        safePayload.remove("id");

        // Si viene email en body, debe coincidir
        Object bodyEmail = safePayload.get("email");
        if (bodyEmail != null && !email.equalsIgnoreCase(String.valueOf(bodyEmail))) {
            return ResponseEntity.status(400).body(Map.of(
                    "message", "El email no puede modificarse."
            ));
        }
        safePayload.put("email", user.getEmail());

        // Guardar firstName/lastName en columnas si vienen
        if (safePayload.containsKey("firstName")) {
            user.setFirstName(trimToNull(safePayload.get("firstName")));
        }
        if (safePayload.containsKey("lastName")) {
            user.setLastName(trimToNull(safePayload.get("lastName")));
        }

        // Preparar JSON extendido: removemos claves que ya van en columnas (para evitar duplicado)
        Map<String, Object> ext = new LinkedHashMap<>(safePayload);
        ext.remove("email");
        ext.remove("firstName");
        ext.remove("lastName");

        try {
            String json = objectMapper.writeValueAsString(ext);
            user.setProfileJson(json);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of(
                    "message", "No se pudo guardar el perfil (JSON inválido)."
            ));
        }

        userRepo.save(user);

        // Responder con el perfil completo (como GET)
        return getProfile(principal);
    }

    /**
     * Endpoint legacy usado en algunas pantallas.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> me(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(401).build();
        }

        String email = principal.getName();
        List<TravelRequest> requests = requestRepo.findByEmailIgnoreCase(email);
        if (requests == null || requests.isEmpty()) {
            UserProfileDto dto = new UserProfileDto();
            dto.email = email;
            dto.fullName = null;
            dto.firstName = null;
            dto.lastName = null;
            return ResponseEntity.ok(dto);
        }

        TravelRequest latest = requests.stream()
                .max(Comparator.comparing(TravelRequest::getCreatedAt))
                .orElse(null);

        String fullName = latest != null ? latest.getName() : null;
        String firstName = null;
        String lastName = null;
        if (fullName != null && !fullName.isBlank()) {
            String[] parts = fullName.trim().split("\\s+", 2);
            firstName = parts.length > 0 ? parts[0] : null;
            lastName = parts.length > 1 ? parts[1] : null;
        }

        UserProfileDto dto = new UserProfileDto();
        dto.email = email;
        dto.fullName = fullName;
        dto.firstName = firstName;
        dto.lastName = lastName;
        return ResponseEntity.ok(dto);
    }

    private Map<String, Object> parseProfileJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static void putIfAbsentNonBlank(Map<String, Object> out, String key, String value) {
        if (out.containsKey(key)) return;
        String v = safe(value);
        if (v != null) out.put(key, v);
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private static String trimToNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }
}
