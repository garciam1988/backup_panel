package app.coincidir.api.web.admin.backups;

import app.coincidir.api.web.admin.backups.dto.BackupSettingsDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ejecuta el backup diario en base a la configuración del panel.
 *
 * Nota: Se usa un archivo de estado para evitar ejecutar más de una vez por día.
 */
@RequiredArgsConstructor
public class BackupsDailyScheduler {

    private final BackupsService backupsService;
    private final BackupsProperties props;
    private final ObjectMapper om;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public void tick() {
        // Deshabilitado: la ejecución diaria se unifica en BackupsScheduler -> BackupsService.tryRunDailyIfDue().
        // Se deja esta clase para compatibilidad, pero sin scheduling para evitar doble ejecución.
        if (!running.compareAndSet(false, true)) return;
        try {
            BackupSettingsDto settings = backupsService.getSettings();
            if (settings == null || !settings.isEnabled()) return;

            LocalTime target;
            try {
                target = LocalTime.parse(String.valueOf(settings.getDailyTime()).trim());
            } catch (Exception ex) {
                return;
            }

            ZoneId zone = resolveZone();
            ZonedDateTime znow = ZonedDateTime.now(zone);
            LocalDate today = znow.toLocalDate();
            LocalTime now = znow.toLocalTime().truncatedTo(ChronoUnit.MINUTES);

            long diffMin = Duration.between(target, now).toMinutes();
            // Ventana tolerante para no perder el minuto exacto por drift (0..2 min)
            if (diffMin < 0 || diffMin > 2) return;

            DailyState st = readState();
            if (st != null && st.lastRunDate != null && st.lastRunDate.equals(today)) return;

            backupsService.forceBackup("DAILY");
            writeState(new DailyState(today));
        } finally {
            running.set(false);
        }
    }

    private ZoneId resolveZone() {
        try {
            String z = props == null ? null : props.getTimezone();
            if (z == null || z.isBlank()) return ZoneId.systemDefault();
            return ZoneId.of(z.trim());
        } catch (Exception ex) {
            return ZoneId.systemDefault();
        }
    }

    private Path dumpsDir() {
        String configured = props != null ? props.getDir() : null;
        if (configured == null || configured.isBlank()) configured = "dumps";

        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p;
        return Paths.get(System.getProperty("user.dir"), configured);
    }

    private Path stateFile() {
        return dumpsDir().resolve("backup-daily-state.json");
    }

    private DailyState readState() {
        try {
            Path f = stateFile();
            if (!Files.exists(f)) return null;
            return om.readValue(f.toFile(), DailyState.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeState(DailyState st) {
        try {
            Path dir = dumpsDir();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            om.writerWithDefaultPrettyPrinter().writeValue(stateFile().toFile(), st);
        } catch (Exception ignored) {
        }
    }

    private static class DailyState {
        public LocalDate lastRunDate;

        public DailyState() {}

        public DailyState(LocalDate lastRunDate) {
            this.lastRunDate = lastRunDate;
        }
    }
}
