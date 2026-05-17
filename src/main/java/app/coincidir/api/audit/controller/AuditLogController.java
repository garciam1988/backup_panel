package app.coincidir.api.audit.controller;

import app.coincidir.api.audit.domain.AuditLog;
import app.coincidir.api.audit.repository.AuditLogRepository;
import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditLogController — endpoints del módulo de auditoría para /admin.
 *
 * Acceso restringido: solo usuarios con fullAccess (DIOS) o con el flag
 * canViewAudit en sus permisos efectivos pueden listar/exportar logs.
 *
 * Endpoints:
 *  GET  /api/admin/audit-log              — listado paginado con filtros
 *  GET  /api/admin/audit-log/{id}         — detalle de un log (con diff parseado)
 *  GET  /api/admin/audit-log/filters      — opciones disponibles para los selects
 *  GET  /api/admin/audit-log/export.csv   — export CSV con los filtros aplicados
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository repo;
    private final PanelUserRepository userRepo;
    private final PermissionsService permissionsService;

    // ── Listado paginado ──────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from,         // ISO datetime
            @RequestParam(required = false) String to,           // ISO datetime
            @RequestParam(required = false) String q,            // búsqueda libre
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Principal principal) {

        requireAuditPermission(principal);

        Instant fromIns = parseIsoOrNull(from);
        Instant toIns = parseIsoOrNull(to);
        // Si el caller mandó q vacío, lo tratamos como null para no romper LIKE
        String qNorm = (q != null && !q.isBlank()) ? q.trim() : null;
        String moduleNorm = (module != null && !module.isBlank()) ? module.trim() : null;
        String actionNorm = (action != null && !action.isBlank()) ? action.trim() : null;
        String entityTypeNorm = (entityType != null && !entityType.isBlank()) ? entityType.trim() : null;

        // Cap defensivo del page size — evitar que alguien pida 10000 filas.
        int effectiveSize = Math.min(Math.max(size, 1), 200);

        Pageable pageable = PageRequest.of(page, effectiveSize);
        Page<AuditLog> result = repo.search(moduleNorm, actionNorm, userId, entityTypeNorm,
                                            fromIns, toIns, qNorm, pageable);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.getContent().stream().map(this::toListDto).collect(Collectors.toList()));
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        return out;
    }

    // ── Detalle ──────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public AuditLogDetailDto detail(@PathVariable Long id, Principal principal) {
        requireAuditPermission(principal);
        AuditLog log = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Log no encontrado"));
        return toDetailDto(log);
    }

    // ── Filtros disponibles (para poblar selects en UI) ───────────────────

    /**
     * Devuelve las opciones distintas presentes en los logs actuales:
     * módulos, acciones, entityTypes y usuarios. La UI usa esto para
     * armar selects que no muestren opciones vacías.
     */
    @GetMapping("/filters")
    public Map<String, Object> filterOptions(Principal principal) {
        requireAuditPermission(principal);

        // Estos selects los podríamos cachear (los valores no cambian rápido),
        // pero como el módulo se usa poco frecuentemente, una query directa
        // por filtro alcanza. Si el volumen crece, considerar cache de 5 min.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", repo.findAll().stream()
            .map(AuditLog::getModule)
            .filter(Objects::nonNull)
            .distinct().sorted().limit(20)
            .collect(Collectors.toList()));
        out.put("actions", repo.findAll().stream()
            .map(AuditLog::getAction)
            .filter(Objects::nonNull)
            .distinct().sorted().limit(100)
            .collect(Collectors.toList()));
        out.put("entityTypes", repo.findAll().stream()
            .map(AuditLog::getEntityType)
            .filter(Objects::nonNull)
            .distinct().sorted().limit(50)
            .collect(Collectors.toList()));
        // Usuarios con al menos 1 log
        out.put("users", repo.findAll().stream()
            .filter(l -> l.getUserId() != null)
            .collect(Collectors.toMap(
                AuditLog::getUserId,
                l -> Map.of("id", l.getUserId(), "username", l.getUsername() != null ? l.getUsername() : "?"),
                (a, b) -> a))
            .values());
        return out;
    }

    // ── Export CSV ───────────────────────────────────────────────────────

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            Principal principal) {

        requireAuditPermission(principal);

        Instant fromIns = parseIsoOrNull(from);
        Instant toIns = parseIsoOrNull(to);
        String qNorm = (q != null && !q.isBlank()) ? q.trim() : null;
        String moduleNorm = (module != null && !module.isBlank()) ? module.trim() : null;
        String actionNorm = (action != null && !action.isBlank()) ? action.trim() : null;
        String entityTypeNorm = (entityType != null && !entityType.isBlank()) ? entityType.trim() : null;

        // Cap defensivo: hasta 10k filas por export. Si necesitan más,
        // que filtren por rango más chico.
        Pageable pageable = PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "ts"));
        Page<AuditLog> result = repo.search(moduleNorm, actionNorm, userId, entityTypeNorm,
                                            fromIns, toIns, qNorm, pageable);

        StringBuilder sb = new StringBuilder();
        sb.append("fecha,usuario,rol,modulo,accion,entidad,id_entidad,resumen,ip\n");
        for (AuditLog l : result.getContent()) {
            sb.append(csvField(l.getTs() != null ? l.getTs().toString() : "")).append(",");
            sb.append(csvField(l.getUsername())).append(",");
            sb.append(csvField(l.getRole())).append(",");
            sb.append(csvField(l.getModule())).append(",");
            sb.append(csvField(l.getAction())).append(",");
            sb.append(csvField(l.getEntityType())).append(",");
            sb.append(csvField(l.getEntityId())).append(",");
            sb.append(csvField(l.getSummary())).append(",");
            sb.append(csvField(l.getIpAddress())).append("\n");
        }

        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"audit-log.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(sb.toString());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Verifica que el usuario actual tenga permiso para ver auditoría.
     * Permitido si: fullAccess (DIOS) o el rol tiene la admin section "audit"
     * habilitada (a través de adminSections en el rol o override del usuario).
     * Cualquier otro caso → 403.
     *
     * El admin section "audit" no existía antes — la introducimos con este
     * módulo. Los roles que no la tengan no podrán entrar. DIOS la tiene
     * automáticamente por fullAccess.
     */
    private void requireAuditPermission(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        PanelUser u = userRepo.findByUsername(principal.getName()).orElse(null);
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }
        EffectivePermissions perms = permissionsService.resolve(u);
        // fullAccess (DIOS) siempre puede. Si no, chequeamos la section.
        if (!perms.fullAccess() && !perms.hasAdminSection("audit")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tu rol no tiene permiso para ver auditoría.");
        }
    }

    private Instant parseIsoOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            // Intento alternativo con offsets habituales (ej: "2026-05-17T03:00:00Z")
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (Exception ignored) { }
            try {
                return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception ignored) { }
            return null;
        }
    }

    private String csvField(String s) {
        if (s == null) return "";
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n");
        String esc = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + esc + "\"" : esc;
    }

    private AuditLogListDto toListDto(AuditLog l) {
        AuditLogListDto d = new AuditLogListDto();
        d.id = l.getId();
        d.ts = l.getTs();
        d.userId = l.getUserId();
        d.username = l.getUsername();
        d.role = l.getRole();
        d.action = l.getAction();
        d.entityType = l.getEntityType();
        d.entityId = l.getEntityId();
        d.entityLabel = l.getEntityLabel();
        d.module = l.getModule();
        d.summary = l.getSummary();
        d.source = l.getSource();
        // changesJson NO se incluye en el listado para no inflar el payload.
        d.hasChanges = l.getChangesJson() != null && !l.getChangesJson().isBlank()
                       && !"{}".equals(l.getChangesJson());
        return d;
    }

    private AuditLogDetailDto toDetailDto(AuditLog l) {
        AuditLogDetailDto d = new AuditLogDetailDto();
        d.id = l.getId();
        d.ts = l.getTs();
        d.userId = l.getUserId();
        d.username = l.getUsername();
        d.role = l.getRole();
        d.action = l.getAction();
        d.entityType = l.getEntityType();
        d.entityId = l.getEntityId();
        d.entityLabel = l.getEntityLabel();
        d.module = l.getModule();
        d.summary = l.getSummary();
        d.source = l.getSource();
        d.ipAddress = l.getIpAddress();
        d.userAgent = l.getUserAgent();
        d.changesJson = l.getChangesJson();
        return d;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditLogListDto {
        public Long id;
        public Instant ts;
        public Long userId;
        public String username;
        public String role;
        public String action;
        public String entityType;
        public String entityId;
        public String entityLabel;
        public String module;
        public String summary;
        public String source;
        public Boolean hasChanges;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditLogDetailDto extends AuditLogListDto {
        public String ipAddress;
        public String userAgent;
        public String changesJson;
    }
}
