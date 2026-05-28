package app.coincidir.api.service.backups;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CronJobsAdminController — endpoints del módulo "Cron Jobs" del AdminPanel.
 *
 * Por ahora gestiona un solo job: el backup de reservas del día. La estructura
 * (lista de jobs) deja lugar para sumar más en el futuro sin romper la UI.
 *
 * Bajo /api/admin/** → requiere autenticación (ver SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cron-jobs")
@RequiredArgsConstructor
public class CronJobsAdminController {

    private final ReservasBackupService reservasBackup;

    /** Config + estado de la última corrida del backup de reservas. */
    @GetMapping("/reservas-backup")
    public ReservasBackupConfig getReservasBackup() {
        return reservasBackup.getConfig();
    }

    /** Actualiza la config del backup de reservas. */
    @PutMapping("/reservas-backup")
    public ReservasBackupConfig updateReservasBackup(@RequestBody Map<String, Object> body) {
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
        Integer interval = null;
        Object iv = body.get("intervalMinutes");
        if (iv instanceof Number) interval = ((Number) iv).intValue();
        else if (iv instanceof String && !((String) iv).isBlank()) {
            try { interval = Integer.parseInt(((String) iv).trim()); } catch (Exception ignore) {}
        }
        String tableSlug  = body.get("tableSlug")  instanceof String ? (String) body.get("tableSlug")  : null;
        String dateColumn = body.get("dateColumn") instanceof String ? (String) body.get("dateColumn") : null;
        String filename   = body.get("filename")   instanceof String ? (String) body.get("filename")   : null;

        return reservasBackup.updateConfig(enabled, interval, tableSlug, dateColumn, filename);
    }

    /**
     * Corre el backup AHORA (disparo manual desde la UI). Ignora el flag
     * enabled — un disparo manual siempre corre. Devuelve la config con el
     * resultado de esta corrida ya registrado (lastRunStatus, etc.).
     */
    @PostMapping("/reservas-backup/run")
    public ReservasBackupConfig runReservasBackupNow() {
        return reservasBackup.runBackup(true);
    }
}
