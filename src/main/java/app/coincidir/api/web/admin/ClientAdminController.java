package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.TravelRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints de soporte para la Carga Manual de Pasajero:
 * - Buscar cliente por documento
 * - Validar si un email ya existe
 */
@RestController
@RequestMapping("/api/admin/clients")
@RequiredArgsConstructor
public class ClientAdminController {

    private final TravelRequestRepository travelRequestRepo;
    private final ObjectMapper objectMapper;

    @GetMapping("/by-document")
    public ResponseEntity<?> getByDocument(
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "documentNumber") String documentNumber
    ) {
        String dn = documentNumber == null ? "" : documentNumber.trim().replaceAll("\\s+", "");
        if (dn.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar el Nro de documento.");
        }

        Optional<TravelRequest> opt = travelRequestRepo.findTopByDniOrderByIdDesc(dn);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "found", false
            ));
        }

        TravelRequest tr = opt.get();

        // Intentar recuperar firstName/lastName desde depositNotes si existe (por compat)
        String fn = null;
        String ln = null;
        try {
            String notes = tr.getDepositNotes();
            if (notes != null) {
                notes = notes.trim();
                if (notes.startsWith("{") && notes.endsWith("}")) {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(notes);
                    fn = text(node, "firstName");
                    ln = text(node, "lastName");
                }
            }
        } catch (Exception ignored) {
        }

        if ((fn == null || fn.isBlank()) && (ln == null || ln.isBlank())) {
            String[] parts = splitName(tr.getName());
            fn = parts[0];
            ln = parts[1];
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tr.getId());
        out.put("firstName", fn);
        out.put("lastName", ln);
        out.put("email", tr.getEmail());
        out.put("phone", tr.getPhone());
        out.put("gender", tr.getGender());
        out.put("birthDate", tr.getBirthDate() == null ? null : tr.getBirthDate().toString());
        out.put("countryId", tr.getCountryId());
        out.put("country", tr.getCountry());
        // Preferir columna document_type; si no hay, usar el param de búsqueda
        String resolvedDocTypeOut = (tr.getDocumentType() != null && !tr.getDocumentType().isBlank())
                ? tr.getDocumentType()
                : (documentType == null ? null : documentType.trim().toUpperCase());
        out.put("documentType", resolvedDocTypeOut);
        out.put("documentNumber", dn);
        out.put("found", true);

        return ResponseEntity.ok(out);
    }

    @GetMapping("/email-exists")
    public ResponseEntity<?> emailExists(
            @RequestParam("email") String email,
            @RequestParam(value = "documentNumber", required = false) String documentNumber,
            @RequestParam(value = "excludeClientId", required = false) Long excludeClientId
    ) {
        String e = email == null ? "" : email.trim().toLowerCase();
        String dn = documentNumber == null ? "" : documentNumber.trim().replaceAll("\\s+", "");
        if (e.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar el email.");
        }

        boolean exists = (excludeClientId != null)
                ? travelRequestRepo.existsByEmailIgnoreCaseAndIdNot(e, excludeClientId)
                : travelRequestRepo.existsByEmailIgnoreCase(e);
        boolean sameDocument = exists && !dn.isBlank() && travelRequestRepo.existsByEmailIgnoreCaseAndDni(e, dn);
        boolean allowed = !exists || sameDocument;

        return ResponseEntity.ok(Map.of(
                "exists", exists,
                "sameDocument", sameDocument,
                "allowed", allowed
        ));
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String key) {
        if (node == null || key == null) return null;
        var n = node.get(key);
        if (n == null || n.isNull()) return null;
        String v = n.asText(null);
        if (v == null) return null;
        v = v.trim();
        return v.isBlank() ? null : v;
    }

    private String[] splitName(String full) {
        String s = full == null ? "" : full.trim();
        if (s.isBlank()) return new String[]{null, null};
        String[] tokens = s.split("\\s+");
        if (tokens.length == 1) return new String[]{tokens[0], null};
        String last = tokens[tokens.length - 1];
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < tokens.length - 1; i++) {
            if (i > 0) first.append(' ');
            first.append(tokens[i]);
        }
        String fn = first.toString().trim();
        return new String[]{fn.isBlank() ? null : fn, last.isBlank() ? null : last};
    }
}
