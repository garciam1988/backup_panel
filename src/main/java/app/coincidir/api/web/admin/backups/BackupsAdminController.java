package app.coincidir.api.web.admin.backups;

import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.web.admin.backups.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/backups")
@RequiredArgsConstructor
public class BackupsAdminController {

    @Qualifier("adminBackupsService")
    private final BackupsService service;
    private final AuditService auditService;

    @GetMapping("/settings")
    public BackupSettingsDto getSettings() {
        return service.getSettings();
    }

    @PutMapping("/settings")
    public BackupSettingsDto updateSettings(@RequestBody UpdateBackupSettingsRequest req) {
        BackupSettingsDto result = service.updateSettings(req);

        // Audit: cambios de config de backup son sensibles (afectan retención
        // y políticas). El DTO de request es chico — lo serializamos completo
        // como newValue. No tenemos snapshot previo trivial aquí porque el
        // service hace el update internamente; preferimos un "logAction" con
        // summary explícito a inventar un diff falso.
        try {
            auditService.logAction(
                "backup.settings_update",
                "BackupSettings",
                "1",
                "Configuración de backups",
                "admin",
                "Actualizó la configuración de backups"
            );
        } catch (Exception ignored) {}

        return result;
    }

    @GetMapping({"", "/"})
    public List<BackupFileDto> listBackups() {
        return service.listBackups();
    }

    @PostMapping("/force")
    public Map<String, Object> forceBackup(Authentication auth) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        Map<String, Object> result = service.forceBackup(performedBy);

        try {
            // Si el backup generó un archivo, su nombre suele venir en el result.
            // Lo extraemos para que el log tenga la referencia del dump creado.
            Object filename = result != null ? result.get("filename") : null;
            String entityId = filename != null ? String.valueOf(filename) : "manual";
            auditService.logAction(
                "backup.create",
                "Backup",
                entityId,
                filename != null ? String.valueOf(filename) : "Backup manual",
                "admin",
                "Generó un backup manual"
            );
        } catch (Exception ignored) {}

        return result;
    }

    @PostMapping("/restore/{dumpFileName}")
    public Map<String, Object> restore(
            @PathVariable String dumpFileName,
            @RequestBody RestoreRequest req,
            Authentication auth
    ) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        String code = req != null ? req.getAdminCode() : null;
        Map<String, Object> result = service.restore(dumpFileName, code, performedBy);

        // Audit: el RESTORE es la acción más crítica del módulo. Sobreescribe
        // potencialmente toda la BD. La etiquetamos con prioridad alta — el
        // job de retención la mantiene 1 año (CRITICAL_ACTIONS).
        try {
            auditService.logAction(
                "backup.restore",
                "Backup",
                dumpFileName,
                dumpFileName,
                "admin",
                "Restauró el backup \"" + dumpFileName + "\""
            );
        } catch (Exception ignored) {}

        return result;
    }

    @PostMapping("/delete/{dumpFileName}")
    public Map<String, Object> deleteDump(
            @PathVariable String dumpFileName,
            @RequestBody RestoreRequest req,
            Authentication auth
    ) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        String code = req != null ? req.getAdminCode() : null;
        Map<String, Object> result = service.deleteDump(dumpFileName, code, performedBy);

        try {
            auditService.logAction(
                "backup.delete",
                "Backup",
                dumpFileName,
                dumpFileName,
                "admin",
                "Eliminó el backup \"" + dumpFileName + "\""
            );
        } catch (Exception ignored) {}

        return result;
    }

    @GetMapping("/restore-history")
    public List<RestoreHistoryDto> restoreHistory(
            @RequestParam(required = false, defaultValue = "200") int limit
    ) {
        return service.restoreHistory(limit);
    }
}
