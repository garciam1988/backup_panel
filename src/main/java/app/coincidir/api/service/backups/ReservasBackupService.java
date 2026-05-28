package app.coincidir.api.service.backups;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.service.BotTableImportExportService;
import app.coincidir.api.web.admin.backups.BackupsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReservasBackupService — backup de contingencia de las reservas del DÍA.
 *
 * Genera un .xlsx con las reservas cuya fecha de referencia cae hoy y lo sube
 * a Git (GitHub) reusando el mecanismo de push del backup diario. GitHub es
 * independiente de Railway, así que el último snapshot sobrevive una caída.
 *
 * La configuración y el estado de la última corrida viven en la tabla
 * reservas_backup_config (singleton id=1), editable desde el AdminPanel
 * (módulo "Cron Jobs"). SOBREESCRIBE siempre el mismo archivo; Git conserva
 * el historial de versiones.
 *
 * Best-effort: cualquier fallo se loguea y se registra en la config, nunca
 * tumba la app.
 */
@Slf4j
@Service
public class ReservasBackupService {

    private final BotTableRepository tableRepo;
    private final BotTableImportExportService importExport;
    private final BackupsService backupsService;
    private final ReservasBackupConfigRepository configRepo;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ReservasBackupService(BotTableRepository tableRepo,
                                 BotTableImportExportService importExport,
                                 BackupsService backupsService,
                                 ReservasBackupConfigRepository configRepo) {
        this.tableRepo = tableRepo;
        this.importExport = importExport;
        this.backupsService = backupsService;
        this.configRepo = configRepo;
    }

    /** Devuelve la config (singleton). La crea con defaults si no existe. */
    public ReservasBackupConfig getConfig() {
        return configRepo.findById(1L).orElseGet(() -> {
            ReservasBackupConfig c = new ReservasBackupConfig();
            c.setId(1L);
            return configRepo.save(c);
        });
    }

    /** Actualiza los campos editables de la config. Devuelve la config guardada. */
    public ReservasBackupConfig updateConfig(Boolean enabled, Integer intervalMinutes,
                                             String tableSlug, String dateColumn, String filename) {
        ReservasBackupConfig c = getConfig();
        if (enabled != null) c.setEnabled(enabled);
        if (intervalMinutes != null && intervalMinutes > 0) c.setIntervalMinutes(intervalMinutes);
        if (tableSlug != null && !tableSlug.isBlank()) c.setTableSlug(tableSlug.trim());
        if (dateColumn != null && !dateColumn.isBlank()) c.setDateColumn(dateColumn.trim());
        if (filename != null && !filename.isBlank()) {
            String f = filename.trim();
            if (!f.toLowerCase().endsWith(".xlsx")) f = f + ".xlsx";
            c.setFilename(f);
        }
        return configRepo.save(c);
    }

    /**
     * Corre el backup AHORA según la config actual. Best-effort: nunca lanza.
     * Si dos corridas se solapan, la segunda se descarta.
     *
     * @param manual true si lo disparó un admin desde la UI (ignora el flag
     *               enabled — un disparo manual siempre corre). false si vino
     *               del scheduler (respeta enabled).
     * @return la config con el resultado de esta corrida ya registrado.
     */
    public ReservasBackupConfig runBackup(boolean manual) {
        ReservasBackupConfig cfg = getConfig();
        if (!manual && !Boolean.TRUE.equals(cfg.getEnabled())) return cfg;

        if (!running.compareAndSet(false, true)) {
            log.debug("[ReservasBackup] corrida anterior aún en curso, salteo");
            return cfg;
        }
        try {
            ZoneId zone = backupsService.getDailyZone();
            Optional<BotTable> opt = tableRepo.findBySlug(cfg.getTableSlug());
            if (opt.isEmpty()) {
                return record(cfg, "ERROR",
                        "No existe tabla con slug '" + cfg.getTableSlug() + "'", null, zone);
            }
            BotTable table = opt.get();

            byte[] xlsx = importExport.exportTodayToXlsx(table, cfg.getDateColumn(), zone);
            int rows = importExport.countTodayRecords(table, cfg.getDateColumn(), zone);

            String now = LocalDateTime.now(zone).format(TS);
            String msg = "Backup reservas del dia [" + now + "] (" + rows + " filas)";
            backupsService.publishFileToGit(cfg.getFilename(), xlsx, msg);

            log.info("[ReservasBackup] OK — {} ({} filas, {} bytes) subido a git",
                    cfg.getFilename(), rows, xlsx.length);
            return record(cfg, "OK", rows + " reserva(s) de hoy respaldadas", rows, zone);
        } catch (Throwable ex) {
            // Throwable (no solo Exception): UnsatisfiedLinkError y
            // NoClassDefFoundError son Error, no Exception. Si el SO no tiene
            // libfreetype y POI gatilla fuentes, atrapamos igual y registramos
            // un error del job en vez de devolver un 500 crudo al endpoint.
            log.error("[ReservasBackup] falló: {}", ex.getMessage(), ex);
            return record(cfg, "ERROR", trimMsg(String.valueOf(ex.getMessage())), null,
                    backupsService.getDailyZone());
        } finally {
            running.set(false);
        }
    }

    private ReservasBackupConfig record(ReservasBackupConfig cfg, String status,
                                        String message, Integer rows, ZoneId zone) {
        cfg.setLastRunAt(Instant.now());
        cfg.setLastRunStatus(status);
        cfg.setLastRunMessage(trimMsg(message));
        cfg.setLastRunRows(rows);
        return configRepo.save(cfg);
    }

    private static String trimMsg(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 497) + "..." : s;
    }
}
