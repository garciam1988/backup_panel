package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.StaffUser;
import app.coincidir.api.marketing.service.StaffUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestión de StaffUsers (mozos / cajeros) desde el panel admin de Marketing.
 *
 * Requiere JWT admin (mismo que /marketing). Permite CRUD y reset de PIN.
 *
 * Endpoints:
 *   GET  /api/admin/marketing/staff-users         Lista todos
 *   POST /api/admin/marketing/staff-users         Crear nuevo mozo
 *   PUT  /api/admin/marketing/staff-users/{id}    Editar (nombre, rol, active)
 *   POST /api/admin/marketing/staff-users/{id}/reset-pin   Reset del PIN
 *   DELETE /api/admin/marketing/staff-users/{id}  Soft delete (active=false)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing/staff-users")
@RequiredArgsConstructor
public class StaffUserAdminController {

    private final StaffUserService service;

    @GetMapping
    public ResponseEntity<?> listAll() {
        List<Map<String, Object>> result = service.listAll().stream()
            .map(StaffUserAdminController::toDto)
            .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String pin = (String) body.get("pin");
            String role = (String) body.get("role");
            StaffUser s = service.create(name, pin, role);
            return ResponseEntity.ok(toDto(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creando staff user", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Error interno"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String role = (String) body.get("role");
            Boolean active = (Boolean) body.get("active");
            StaffUser s = service.update(id, name, role, active);
            return ResponseEntity.ok(toDto(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reset-pin")
    public ResponseEntity<?> resetPin(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String newPin = (String) body.get("newPin");
            StaffUser s = service.resetPin(id, newPin);
            return ResponseEntity.ok(toDto(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable Long id) {
        try {
            service.deactivate(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toDto(StaffUser s) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", s.getId());
        dto.put("name", s.getName());
        dto.put("role", s.getRole());
        dto.put("active", s.getActive());
        dto.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        dto.put("lastLoginAt", s.getLastLoginAt() != null ? s.getLastLoginAt().toString() : null);
        return dto;
    }
}
