package app.coincidir.api.web.admin.backups;

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

    @GetMapping("/settings")
    public BackupSettingsDto getSettings() {
        return service.getSettings();
    }

    @PutMapping("/settings")
    public BackupSettingsDto updateSettings(@RequestBody UpdateBackupSettingsRequest req) {
        return service.updateSettings(req);
    }

    @GetMapping({"", "/"})
    public List<BackupFileDto> listBackups() {
        return service.listBackups();
    }

    @PostMapping("/force")
    public Map<String, Object> forceBackup(Authentication auth) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        return service.forceBackup(performedBy);
    }

    @PostMapping("/restore/{dumpFileName}")
    public Map<String, Object> restore(
            @PathVariable String dumpFileName,
            @RequestBody RestoreRequest req,
            Authentication auth
    ) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        String code = req != null ? req.getAdminCode() : null;
        return service.restore(dumpFileName, code, performedBy);
    }

    @PostMapping("/delete/{dumpFileName}")
    public Map<String, Object> deleteDump(
            @PathVariable String dumpFileName,
            @RequestBody RestoreRequest req,
            Authentication auth
    ) {
        String performedBy = auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
        String code = req != null ? req.getAdminCode() : null;
        return service.deleteDump(dumpFileName, code, performedBy);
    }

    @GetMapping("/restore-history")
    public List<RestoreHistoryDto> restoreHistory(
            @RequestParam(required = false, defaultValue = "200") int limit
    ) {
        return service.restoreHistory(limit);
    }
}
