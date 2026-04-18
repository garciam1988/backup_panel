package app.coincidir.api.web.admin.backups;

import app.coincidir.api.web.admin.backups.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

@Service("adminBackupsService")
public class BackupsService {

    private static final Logger log = LoggerFactory.getLogger(BackupsService.class);

    private static final String BACKUP_GIT_REMOTE_URL = "https://github.com/garciam1988/backup_panel.git";
    private static final String BACKUP_GIT_PUSH_BRANCH = "main";

    private static final DateTimeFormatter TS_ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private static final String TRIGGER_MANUAL = "MANUAL";
    private static final String TRIGGER_AUTOMATICO = "AUTOMATICO";

    private final BackupsProperties props;
    private final Environment env;
    private final DataSource dataSource;
    private final ObjectMapper om;

    // Evita re-ejecuciones concurrentes del backup diario
    private final AtomicBoolean runningDaily = new AtomicBoolean(false);

    public BackupsService(BackupsProperties props, Environment env, DataSource dataSource, ObjectMapper om) {
        this.props = props;
        this.env = env;
        this.dataSource = dataSource;
        this.om = om;
    }

    public BackupSettingsDto getSettings() {
        BackupSettingsDto s = readSettings();
        boolean changed = false;

        if (s == null) {
            s = new BackupSettingsDto(true, "02:00", null, null);
            changed = true;
        }

        if (s.getDumpsDir() == null || s.getDumpsDir().isBlank()) {
            String auto = autoDefaultDumpsDir();
            if (auto != null && !auto.isBlank()) {
                s.setDumpsDir(auto);
                changed = true;
            } else {
                String fallback = props != null ? props.getDir() : null;
                if (fallback != null && !fallback.isBlank()) {
                    s.setDumpsDir(fallback);
                    changed = true;
                } else {
                    // Último fallback: carpeta relativa al working dir
                    s.setDumpsDir("dumps");
                    changed = true;
                }
            }
        }

        if (changed) {
            writeSettings(s);
        }

        return s;
    }

    public BackupSettingsDto updateSettings(UpdateBackupSettingsRequest req) {
        BackupSettingsDto current = getSettings();
        if (req != null) {
            if (req.getEnabled() != null) current.setEnabled(req.getEnabled());
            if (req.getDailyTime() != null && !req.getDailyTime().isBlank()) {
                String prev = current.getDailyTime();
                String raw = req.getDailyTime().trim();
                try {
                    LocalTime t = LocalTime.parse(raw);
                    String next = t.format(TIME_HHMM);
                    current.setDailyTime(next);

                    // Si cambió el horario, permitir que el scheduler vuelva a correr el mismo día
                    // (útil para pruebas / cambios de horario).
                    if (prev == null || !prev.equals(next)) {
                        current.setLastDailyRun(null);
                    }
                } catch (Exception ex) {
                    // Mantener el valor anterior si el formato no es válido
                }
            }

            String requestedDir = req.getDumpsDir();
            if ((requestedDir == null || requestedDir.isBlank()) && req.getDumpPath() != null) {
                requestedDir = req.getDumpPath();
            }

            if (requestedDir != null) {
                String raw = String.valueOf(requestedDir).trim();
                if (!raw.isBlank()) {
                    try {
                        Files.createDirectories(resolvePath(raw));
                    } catch (Exception ex) {
                        throw new RuntimeException("No se pudo crear la carpeta de dumps: " + rootMessage(ex));
                    }
                    current.setDumpsDir(raw);
                }
            }
        }
        writeSettings(current);
        // Releer para asegurar que quedó persistido donde corresponde
        return getSettings();
    }

    /**
     * Chequea si corresponde ejecutar el backup diario según el horario configurado.
     * Se ejecuta a lo sumo una vez por día, y si se pasó la hora (ej: app reiniciada), corre al primer tick.
     */
    public void tryRunDailyIfDue() {
        BackupSettingsDto s = getSettings();
        if (s == null || !s.isEnabled()) return;

        LocalTime target;
        try {
            target = LocalTime.parse(s.getDailyTime()).withSecond(0).withNano(0);
        } catch (Exception ex) {
            return;
        }

        ZoneId zone = resolveDailyZone();
        LocalDate today = LocalDate.now(zone);
        LocalTime now = LocalTime.now(zone).withSecond(0).withNano(0);

        if (now.isBefore(target)) return;

        String last = s.getLastDailyRun();
        if (last != null && !last.isBlank()) {
            try {
                if (LocalDate.parse(last.trim(), DATE_ISO).isEqual(today)) return;
            } catch (Exception ignore) {
            }
        }

        if (!runningDaily.compareAndSet(false, true)) return;
        try {
            // Marcar el día antes de ejecutar para evitar loops si el backup falla.
            s.setLastDailyRun(today.format(DATE_ISO));
            writeSettings(s);
            forceBackup(TRIGGER_AUTOMATICO);
        } finally {
            runningDaily.set(false);
        }
    }

    public List<BackupFileDto> listBackups() {
        try {
            Path dir = dumpsDir();
            if (!Files.exists(dir)) Files.createDirectories(dir);

            return Files.list(dir)
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .map(p -> {
                        long size = p.toFile().length();
                        String status = size > 0 ? "SUCCESS" : "FAILED";
                        String msg = size > 0 ? "" : "Archivo vacío (0B)";

                        BackupMeta meta = readMeta(p);
                        String trigger = meta != null && meta.trigger != null && !meta.trigger.isBlank()
                                ? meta.trigger
                                : TRIGGER_MANUAL;

                        String createdAt;
                        if (meta != null && meta.createdAt != null && !meta.createdAt.isBlank()) {
                            createdAt = meta.createdAt;
                        } else {
                            createdAt = OffsetDateTime.ofInstant(new Date(p.toFile().lastModified()).toInstant(), ZoneOffset.UTC)
                                    .format(TS_ISO);
                        }

                        return new BackupFileDto(
                                p.getFileName().toString(),
                                p.toAbsolutePath().toString(),
                                size,
                                createdAt,
                                trigger,
                                status,
                                msg
                        );
                    })
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            // mantener contrato: nunca romper el endpoint
            return new ArrayList<>();
        }
    }

    public Map<String, Object> forceBackup(String performedBy) {
        Path outFile = null;
        Path errFile = null;
        try {
            BackupSettingsDto settings = getSettings();
            if (settings != null && !settings.isEnabled()) {
                return fail("Backups deshabilitados");
            }

            Path dir = dumpsDir();
            if (!Files.exists(dir)) Files.createDirectories(dir);

            DbInfo db = resolveDb();

            if (db.database == null || db.database.isBlank()) {
                return fail("No se pudo determinar el nombre de la base de datos. Verificá spring.datasource.url (debe incluir /<db>)." );
            }

            OffsetDateTime startedAt = OffsetDateTime.now(resolveDailyZone());
            String ts = startedAt.withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            // Mantener nombres simples/consistentes para el panel
            String fileName = safeName(db.database) + "_" + ts + ".sql";
            outFile = dir.resolve(fileName);
            errFile = dir.resolve(fileName + ".err");

            List<String> cmd = new ArrayList<>();
            cmd.add(resolveMysqlBin("mysqldump"));
            cmd.add("-h"); cmd.add(db.host);
            cmd.add("-P"); cmd.add(String.valueOf(db.port));
            cmd.add("-u"); cmd.add(db.username);
            cmd.add("-p" + db.password);
            cmd.add("--single-transaction");
            cmd.add("--routines");
            cmd.add("--events");
            cmd.add(db.database);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Evitar piping manual (en algunos entornos puede terminar en 0B). Redirigir a archivos.
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());

            Process p = pb.start();

            int code = p.waitFor();
            if (code != 0) {
                String tail = readTail(errFile, 4000);
                if (tail.isBlank()) tail = readTail(outFile, 4000);
                Files.deleteIfExists(outFile);
                Files.deleteIfExists(errFile);
                return fail("mysqldump exit code " + code + (tail.isBlank() ? "" : (": " + tail)));
            }

            long size = outFile.toFile().length();
            if (size <= 0) {
                String tail = readTail(errFile, 4000);
                Files.deleteIfExists(outFile);
                Files.deleteIfExists(errFile);
                return fail("mysqldump generó un archivo vacío (0B)." + (tail.isBlank() ? "" : (" " + tail)));
            }

            Files.deleteIfExists(errFile);

            // Guardar metadata (.sql.meta.json) para poder mostrar MANUAL/AUTOMATICO en el panel.
            writeMeta(outFile, new BackupMeta(
                    normalizeTrigger(performedBy),
                    performedBy,
                    startedAt.format(TS_ISO)
            ));

            // Subir los dumps al repo git (solo carpeta dumps). No interrumpir el backup si falla.
            try {
                commitAndPushDumpsToGit(dir);
            } catch (Exception ex) {
                log.warn("No se pudo subir el backup al git: {}", rootMessage(ex));
            }

            Map<String, Object> out = ok();
            out.put("fileName", fileName);
            out.put("filePath", outFile.toAbsolutePath().toString());
            out.put("sizeBytes", size);
            out.put("performedBy", performedBy);
            return out;
        } catch (Exception ex) {
            // Si el proceso no pudo arrancar (mysqldump no encontrado, etc.) puede quedar un .sql 0B: limpiarlo.
            try {
                if (outFile != null && Files.exists(outFile) && Files.size(outFile) == 0) Files.deleteIfExists(outFile);
                if (errFile != null && Files.exists(errFile) && Files.size(errFile) == 0) Files.deleteIfExists(errFile);
            } catch (Exception ignore) {
            }

            String msg = rootMessage(ex);
            String low = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
            if (low.contains("createprocess error=2") || low.contains("no such file") || low.contains("cannot run program")) {
                return fail("No se encontró mysqldump. Configurá COINCIDIR_BACKUPS_MYSQL_BIN apuntando a la carpeta bin de MySQL (ej: C:/wamp64/bin/mysql/mysql9.1.0/bin o C:/Program Files/MySQL/MySQL Server 8.0/bin)." );
            }
            return fail(msg);
        }
    }

    public Map<String, Object> restore(String dumpFileName, String adminCode, String performedBy) {
        RestoreHistoryDto h = new RestoreHistoryDto();
        h.setDumpFileName(dumpFileName);
        h.setPerformedBy(performedBy);
        h.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC).format(TS_ISO));
        h.setStatus("RUNNING");

        List<RestoreHistoryDto> history = readRestoreHistoryInternal();
        history.add(0, h);
        writeRestoreHistory(history);

        try {
            if (!Objects.equals(adminCode, props.getAdminCode())) {
                h.setStatus("FAILED");
                h.setMessage("Código admin inválido");
                return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
            }

            if (dumpFileName == null || dumpFileName.isBlank()) {
                h.setStatus("FAILED");
                h.setMessage("dumpFileName requerido");
                return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
            }

            Path file = dumpsDir().resolve(dumpFileName).normalize();
            if (!file.startsWith(dumpsDir())) {
                h.setStatus("FAILED");
                h.setMessage("dumpFileName inválido");
                return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
            }

            if (!Files.exists(file)) {
                h.setStatus("FAILED");
                h.setMessage("Dump no encontrado: " + dumpFileName);
                return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
            }

            DbInfo db = resolveDb();

            List<String> cmd = new ArrayList<>();
            cmd.add(resolveMysqlBin("mysql"));
            cmd.add("-h"); cmd.add(db.host);
            cmd.add("-P"); cmd.add(String.valueOf(db.port));
            cmd.add("-u"); cmd.add(db.username);
            cmd.add("-p" + db.password);
            cmd.add(db.database);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (OutputStream procIn = p.getOutputStream(); InputStream dumpIn = Files.newInputStream(file)) {
                dumpIn.transferTo(procIn);
            }

            String output;
            try (InputStream is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            int code = p.waitFor();
            if (code != 0) {
                h.setStatus("FAILED");
                h.setMessage((output == null || output.isBlank()) ? ("mysql exit code " + code) : output);
                return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
            }

            h.setStatus("SUCCESS");
            h.setMessage("OK");
            return finalizeHistoryAndReturn(history, h, ok());
        } catch (Exception ex) {
            h.setStatus("FAILED");
            h.setMessage(rootMessage(ex));
            return finalizeHistoryAndReturn(history, h, fail(h.getMessage()));
        }
    }

    public Map<String, Object> deleteDump(String dumpFileName, String adminCode, String performedBy) {
        try {
            if (!Objects.equals(adminCode, props.getAdminCode())) {
                return fail("Código admin inválido");
            }

            if (dumpFileName == null || dumpFileName.isBlank()) {
                return fail("dumpFileName requerido");
            }

            // Solo permitir dumps .sql
            String lower = dumpFileName.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".sql")) {
                return fail("dumpFileName inválido");
            }

            Path dir = dumpsDir();
            Path file = dir.resolve(dumpFileName).normalize();
            if (!file.startsWith(dir)) {
                return fail("dumpFileName inválido");
            }

            if (!Files.exists(file)) {
                return fail("Dump no encontrado: " + dumpFileName);
            }

            Files.delete(file);
            // Limpieza opcional de archivos auxiliares
            Files.deleteIfExists(dir.resolve(dumpFileName + ".err"));
            Files.deleteIfExists(dir.resolve(dumpFileName + ".meta.json"));

            Map<String, Object> out = ok();
            out.put("fileName", dumpFileName);
            out.put("performedBy", performedBy);
            return out;
        } catch (Exception ex) {
            return fail(rootMessage(ex));
        }
    }

    public List<RestoreHistoryDto> restoreHistory(int limit) {
        List<RestoreHistoryDto> items = readRestoreHistoryInternal();
        if (limit <= 0) return items;
        if (items.size() <= limit) return items;
        return items.subList(0, limit);
    }

    // ----------------- helpers -----------------

    private Map<String, Object> finalizeHistoryAndReturn(List<RestoreHistoryDto> history, RestoreHistoryDto h, Map<String, Object> response) {
        h.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC).format(TS_ISO));
        history.set(0, h);
        writeRestoreHistory(history);
        return response;
    }

    private Path dumpsDir() {
        BackupSettingsDto s = readSettings();
        String configured = s != null ? s.getDumpsDir() : null;
        if (configured == null || configured.isBlank()) configured = props != null ? props.getDir() : null;
        if (configured == null || configured.isBlank()) configured = "dumps";
        return resolvePath(configured);
    }

    private Path settingsFile() {
        // Mantener el settings en un directorio "bootstrap" estable (basado en coincidir.backups.dir)
        // para que la ubicación de dumps (editable desde la UI) no deje inaccesibles los settings.
        return bootstrapDir().resolve("backup-settings.json");
    }

    private Path bootstrapDir() {
        String configured = props != null ? props.getDir() : null;
        if (configured == null || configured.isBlank()) configured = "dumps";
        return resolvePath(configured);
    }

    private ZoneId resolveDailyZone() {
        String tz = null;
        try {
            tz = props != null ? props.getTimezone() : null;
        } catch (Exception ignore) {
        }
        if (tz == null || tz.isBlank()) tz = env.getProperty("coincidir.backups.timezone");
        if (tz == null || tz.isBlank()) tz = env.getProperty("coincidir.backups.time-zone");
        if (tz == null || tz.isBlank()) tz = System.getenv("COINCIDIR_BACKUPS_TIME_ZONE");
        ZoneId sys = ZoneId.systemDefault();

        try {
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz.trim());
        } catch (Exception ignore) {
        }

        // Si el sistema está en UTC (común en contenedores), default a Argentina.
        try {
            String id = sys.getId();
            if ("UTC".equalsIgnoreCase(id) || "Etc/UTC".equalsIgnoreCase(id) || "Z".equalsIgnoreCase(id)) {
                return ZoneId.of("America/Argentina/Buenos_Aires");
            }
        } catch (Exception ignore) {
        }

        return sys;
    }

    private BackupSettingsDto readSettings() {
        // 1) Preferir el settings del bootstrap (compat) y, si ese settings define dumpsDir,
        //    permitir que exista un settings junto a los dumps (más intuitivo para el usuario).
        BackupSettingsDto base = readSettingsFrom(settingsFile());

        if (base != null) {
            Path inDumps = settingsFileInDumpsDir(base.getDumpsDir());
            if (inDumps != null) {
                try {
                    Path boot = settingsFile().toAbsolutePath().normalize();
                    Path dumps = inDumps.toAbsolutePath().normalize();
                    if (!boot.equals(dumps) && Files.exists(dumps)) {
                        BackupSettingsDto preferred = readSettingsFrom(dumps);
                        if (preferred != null) return preferred;
                    }
                } catch (Exception ignore) {
                }
            }
            return base;
        }

        // 2) Si no existe en bootstrap, intentar leer desde un dumpsDir inferido.
        String inferred = null;
        try { inferred = autoDefaultDumpsDir(); } catch (Exception ignore) {}
        if (inferred == null || inferred.isBlank()) inferred = props != null ? props.getDir() : null;
        if (inferred == null || inferred.isBlank()) inferred = "dumps";
        return readSettingsFrom(settingsFileInDumpsDir(inferred));
    }

    private void writeSettings(BackupSettingsDto settings) {
        List<Exception> errors = new ArrayList<>();
        boolean wrote = false;

        // Siempre escribir en bootstrap (compat)
        wrote |= tryWriteSettings(settingsFile(), settings, errors);

        // También escribir junto a los dumps configurados (para que el archivo sea fácil de ubicar)
        Path inDumps = settingsFileInDumpsDir(settings != null ? settings.getDumpsDir() : null);
        if (inDumps != null) {
            try {
                Path boot = settingsFile().toAbsolutePath().normalize();
                Path dumps = inDumps.toAbsolutePath().normalize();
                if (!boot.equals(dumps)) {
                    wrote |= tryWriteSettings(inDumps, settings, errors);
                }
            } catch (Exception ex) {
                errors.add(ex);
            }
        }

        if (!wrote) {
            String msg = errors.isEmpty() ? "No se pudo persistir backup-settings.json" : rootMessage(errors.get(0));
            throw new RuntimeException(msg);
        }
    }

    private BackupSettingsDto readSettingsFrom(Path f) {
        try {
            if (f == null || !Files.exists(f)) return null;
            try (InputStream is = Files.newInputStream(f)) {
                return om.readValue(is, BackupSettingsDto.class);
            }
        } catch (Exception ignore) {
            return null;
        }
    }

    private Path settingsFileInDumpsDir(String dumpsDir) {
        try {
            if (dumpsDir == null || dumpsDir.isBlank()) return null;
            return resolvePath(dumpsDir.trim()).resolve("backup-settings.json");
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean tryWriteSettings(Path f, BackupSettingsDto settings, List<Exception> errors) {
        try {
            if (f == null) return false;
            Path dir = f.getParent();
            if (dir != null && !Files.exists(dir)) Files.createDirectories(dir);
            try (OutputStream os = Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                om.writerWithDefaultPrettyPrinter().writeValue(os, settings);
            }
            return true;
        } catch (Exception ex) {
            if (errors != null) errors.add(ex);
            return false;
        }
    }

    private Path resolvePath(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p;
        return Paths.get(System.getProperty("user.dir"), configured);
    }

    private String autoDefaultDumpsDir() {
        try {
            Path p = Paths.get(System.getProperty("user.dir"));
            while (p != null) {
                Path name = p.getFileName();
                String n = name == null ? "" : name.toString();
                if ("Coincidir API".equalsIgnoreCase(n)) {
                    Path root = p.getParent();
                    if (root != null) {
                        return root.resolve("Coincidir Backups Panel")
                                .resolve("coincidir-backups-panel")
                                .resolve("dumps")
                                .toString();
                    }
                }
                if ("Coincidir".equalsIgnoreCase(n)) {
                    return p.resolve("Coincidir Backups Panel")
                            .resolve("coincidir-backups-panel")
                            .resolve("dumps")
                            .toString();
                }
                p = p.getParent();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String normalizeTrigger(String performedBy) {
        if (performedBy == null) return TRIGGER_MANUAL;
        String p = performedBy.trim();
        if (p.isBlank()) return TRIGGER_MANUAL;
        if (TRIGGER_AUTOMATICO.equalsIgnoreCase(p) || "DAILY".equalsIgnoreCase(p) || "AUTO".equalsIgnoreCase(p)) {
            return TRIGGER_AUTOMATICO;
        }
        return TRIGGER_MANUAL;
    }

    private void writeMeta(Path dumpFile, BackupMeta meta) {
        try {
            if (dumpFile == null || meta == null) return;
            Path metaFile = dumpFile.resolveSibling(dumpFile.getFileName().toString() + ".meta.json");
            try (OutputStream os = Files.newOutputStream(metaFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                om.writerWithDefaultPrettyPrinter().writeValue(os, meta);
            }
        } catch (Exception ignore) {
        }
    }

    private BackupMeta readMeta(Path dumpFile) {
        try {
            if (dumpFile == null) return null;
            Path metaFile = dumpFile.resolveSibling(dumpFile.getFileName().toString() + ".meta.json");
            if (!Files.exists(metaFile)) return null;
            try (InputStream is = Files.newInputStream(metaFile)) {
                return om.readValue(is, BackupMeta.class);
            }
        } catch (Exception ignore) {
            return null;
        }
    }

    private static class BackupMeta {
        public String trigger;
        public String performedBy;
        public String createdAt;

        public BackupMeta() {
        }

        public BackupMeta(String trigger, String performedBy, String createdAt) {
            this.trigger = trigger;
            this.performedBy = performedBy;
            this.createdAt = createdAt;
        }
    }

    private Path restoreHistoryFile() {
        return dumpsDir().resolve("restore-history.json");
    }

    private List<RestoreHistoryDto> readRestoreHistoryInternal() {
        try {
            Path f = restoreHistoryFile();
            if (!Files.exists(f)) return new ArrayList<>();
            try (InputStream is = Files.newInputStream(f)) {
                return om.readValue(is, new TypeReference<List<RestoreHistoryDto>>() {});
            }
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private void writeRestoreHistory(List<RestoreHistoryDto> history) {
        try {
            Path dir = dumpsDir();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path f = restoreHistoryFile();
            try (OutputStream os = Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                om.writerWithDefaultPrettyPrinter().writeValue(os, history);
            }
        } catch (Exception ignore) {
        }
    }

    private String resolveMysqlBin(String exeBase) {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String exe = isWindows ? (exeBase + ".exe") : exeBase;

        String configured = env.getProperty("coincidir.backups.mysql-bin");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("COINCIDIR_BACKUPS_MYSQL_BIN");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("COINCIDIR_MYSQL_BIN");
        }
        if (configured != null && !configured.isBlank()) {
            Path p = Paths.get(configured).resolve(exe);
            return p.toAbsolutePath().toString();
        }

        // Intentar buscar en PATH
        String fromPath = findExeInPath(exe, isWindows);
        if (fromPath != null) return fromPath;

        // Fallback: ejecutar which/where para encontrar el binario (Railway/Nix)
        String fromWhich = findExeViaWhich(exe, isWindows);
        if (fromWhich != null) return fromWhich;

        // Fallback: buscar en /nix/store (Railway usa Nix internamente)
        if (!isWindows) {
            String fromNix = findExeInNixStore(exe);
            if (fromNix != null) return fromNix;
        }

        // Autodetección Windows (WAMP / MySQL Server)
        if (isWindows) {
            String auto = findExeInKnownWindowsMysqlBins(exe);
            if (auto != null) return auto;
        }

        return exe;
    }

    private static String findExeViaWhich(String exe, boolean isWindows) {
        try {
            String cmd = isWindows ? "where" : "which";
            Process p = new ProcessBuilder(cmd, exe)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isBlank()) {
                String first = out.split("\\r?\\n")[0].trim();
                if (!first.isBlank()) return first;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String findExeInNixStore(String exe) {
        try {
            // Buscar con 'find' en /nix/store (Railway Nix environment)
            Process p = new ProcessBuilder("find", "/nix/store", "-name", exe, "-type", "f")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isBlank()) {
                // Preferir paths que contengan 'mariadb' o 'mysql'
                String[] lines = out.split("\\r?\\n");
                for (String line : lines) {
                    String t = line.trim();
                    if (t.contains("mariadb") || t.contains("mysql")) return t;
                }
                return lines[0].trim();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String findExeInPath(String exe, boolean isWindows) {
        try {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) return null;
            String[] parts = path.split(isWindows ? ";" : ":");
            for (String part : parts) {
                if (part == null || part.isBlank()) continue;
                Path p = Paths.get(part.trim()).resolve(exe);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String findExeInKnownWindowsMysqlBins(String exe) {
        // WAMP64: C:\\wamp64\\bin\\mysql\\mysqlX.Y.Z\\bin
        String wamp = findInVersionedRoot(Paths.get("C:\\wamp64\\bin\\mysql"), exe);
        if (wamp != null) return wamp;
        String wampOld = findInVersionedRoot(Paths.get("C:\\wamp\\bin\\mysql"), exe);
        if (wampOld != null) return wampOld;

        // XAMPP: C:\\xampp\\mysql\\bin
        Path xampp = Paths.get("C:\\xampp\\mysql\\bin").resolve(exe);
        if (Files.exists(xampp)) return xampp.toAbsolutePath().toString();

        // MySQL Server (Program Files)
        String pf = findInVersionedRoot(Paths.get("C:\\Program Files\\MySQL"), exe);
        if (pf != null) return pf;
        String pf86 = findInVersionedRoot(Paths.get("C:\\Program Files (x86)\\MySQL"), exe);
        if (pf86 != null) return pf86;

        return null;
    }

    private static String findInVersionedRoot(Path root, String exe) {
        try {
            if (!Files.isDirectory(root)) return null;
            List<Path> hits;
            try (var st = Files.list(root)) {
                hits = st.filter(Files::isDirectory)
                        .map(d -> d.resolve("bin").resolve(exe))
                        .filter(Files::exists)
                        .sorted(Comparator.comparing((Path p) -> p.toString()).reversed())
                        .collect(Collectors.toList());
            }
            if (hits.isEmpty()) return null;
            return hits.get(0).toAbsolutePath().toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private DbInfo resolveDb() {
        try {
            String url = env.getProperty("spring.datasource.url");
            String user = env.getProperty("spring.datasource.username", "root");
            String pass = env.getProperty("spring.datasource.password", "1234");

            if (url == null || url.isBlank()) {
                try (var con = dataSource.getConnection()) {
                    String db = con.getCatalog();
                    return new DbInfo("localhost", 3306, db == null ? "" : db, user, pass, url);
                }
            }

            DbInfo info = parseJdbcMysqlUrl(url);
            info.username = user;
            info.password = pass;
            info.jdbcUrl = url;
            return info;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static DbInfo parseJdbcMysqlUrl(String url) {
        // jdbc:mysql://host:port/dbname?params
        String u = url;
        if (u.startsWith("jdbc:")) u = u.substring(5);
        if (u.startsWith("mysql:")) u = u.substring(6);
        if (u.startsWith("//")) u = u.substring(2);

        String hostPortAndPath = u;
        int q = hostPortAndPath.indexOf('?');
        if (q >= 0) hostPortAndPath = hostPortAndPath.substring(0, q);

        String hostPort;
        String path = "";
        int slash = hostPortAndPath.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPortAndPath.substring(0, slash);
            path = hostPortAndPath.substring(slash + 1);
        } else {
            hostPort = hostPortAndPath;
        }

        String host;
        int port = 3306;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            String p = hostPort.substring(colon + 1);
            try { port = Integer.parseInt(p); } catch (Exception ignore) { }
        } else {
            host = hostPort;
        }

        String db = path;
        return new DbInfo(host.isBlank() ? "localhost" : host, port, db == null ? "" : db, null, null, url);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String m = cur.getMessage();
        if (m == null || m.isBlank()) m = cur.getClass().getSimpleName();
        return m;
    }

    private static String readTail(Path file, int maxChars) {
        try {
            if (!Files.exists(file)) return "";
            byte[] bytes = Files.readAllBytes(file);
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.length() <= maxChars) return s;
            return s.substring(s.length() - maxChars);
        } catch (Exception e) {
            return "";
        }
    }


    private void commitAndPushDumpsToGit(Path dumpsDir) {
        if (dumpsDir == null) return;

        // Detectar repo root (si la carpeta no está dentro de un repo, no hacer nada)
        GitExecResult top = gitExec(dumpsDir, Arrays.asList("git", "rev-parse", "--show-toplevel"), 10);
        if (!top.ok()) return;

        Path repoRoot;
        try {
            repoRoot = Paths.get(top.output.trim()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return;
        }

        // Asegurar remote origin
        String originUrl = "";
        GitExecResult origin = gitExec(repoRoot, Arrays.asList("git", "remote", "get-url", "origin"), 10);
        if (origin.ok()) originUrl = origin.output.trim();

        if (originUrl.isBlank()) {
            gitExec(repoRoot, Arrays.asList("git", "remote", "add", "origin", BACKUP_GIT_REMOTE_URL), 10);
        } else if (!originUrl.contains("garciam1988/backup_panel")) {
            // Solo cambiar si claramente no apunta al repo requerido
            gitExec(repoRoot, Arrays.asList("git", "remote", "set-url", "origin", BACKUP_GIT_REMOTE_URL), 10);
        }

        ensureGitIdentity(repoRoot);

        // Stage solo la carpeta de dumps (relativa al repo root)
        Path rel;
        try {
            rel = repoRoot.relativize(dumpsDir.toAbsolutePath().normalize());
        } catch (Exception ex) {
            return;
        }
        String relStr = rel.toString().replace("\\", "/");
        if (relStr.isBlank()) relStr = ".";

        // Asegurar que el commit incluya SOLO cambios de dumps
        gitExec(repoRoot, Arrays.asList("git", "reset"), 10);

        gitExec(repoRoot, Arrays.asList("git", "add", "-A", relStr), 30);

        // ¿Hay cambios staged?
        GitExecResult diff = gitExec(repoRoot, Arrays.asList("git", "diff", "--cached", "--name-only"), 10);
        if (!diff.ok() || diff.output.trim().isEmpty()) return;

        String date = LocalDate.now(resolveDailyZone()).format(DATE_ISO);
        String msg = "Backup automatico diario [" + date + "]";

        GitExecResult commit = gitExec(repoRoot, Arrays.asList("git", "commit", "-m", msg), 30);
        if (!commit.ok()) {
            // Si el commit falla por "nothing to commit", no cortar el flujo
            String low = (commit.output == null ? "" : commit.output.toLowerCase(Locale.ROOT));
            if (low.contains("nothing to commit") || low.contains("no changes")) return;
        }

        // Push (primero tal cual lo pidió el usuario; fallback a HEAD:main)
        GitExecResult push = gitExec(repoRoot, Arrays.asList("git", "push", "-u", "origin", BACKUP_GIT_PUSH_BRANCH), 120);
        if (!push.ok()) {
            gitExec(repoRoot, Arrays.asList("git", "push", "-u", "origin", "HEAD:" + BACKUP_GIT_PUSH_BRANCH), 120);
        }
    }

    private void ensureGitIdentity(Path repoRoot) {
        try {
            GitExecResult name = gitExec(repoRoot, Arrays.asList("git", "config", "user.name"), 10);
            if (!name.ok() || name.output.trim().isEmpty()) {
                gitExec(repoRoot, Arrays.asList("git", "config", "user.name", "Coincidir Backup Bot"), 10);
            }
            GitExecResult email = gitExec(repoRoot, Arrays.asList("git", "config", "user.email"), 10);
            if (!email.ok() || email.output.trim().isEmpty()) {
                gitExec(repoRoot, Arrays.asList("git", "config", "user.email", "backup-bot@coincidir-travel.com"), 10);
            }
        } catch (Exception ignore) {
        }
    }

    private static GitExecResult gitExec(Path workDir, List<String> cmd, int timeoutSeconds) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            p = pb.start();

            boolean finished = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                try { p.destroyForcibly(); } catch (Exception ignore) {}
                return new GitExecResult(124, "timeout");
            }

            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                out = "";
            }

            return new GitExecResult(p.exitValue(), out == null ? "" : out.trim());
        } catch (Exception ex) {
            return new GitExecResult(1, ex.getMessage() == null ? "" : ex.getMessage());
        } finally {
            try { if (p != null) p.getInputStream().close(); } catch (Exception ignore) {}
        }
    }

    private static final class GitExecResult {
        final int code;
        final String output;

        GitExecResult(int code, String output) {
            this.code = code;
            this.output = output;
        }

        boolean ok() { return code == 0; }
    }


    private static String safeName(String s) {
        if (s == null || s.isBlank()) return "db";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", message);
        return m;
    }

    private static final class DbInfo {
        final String host;
        final int port;
        final String database;
        String username;
        String password;
        String jdbcUrl;

        DbInfo(String host, int port, String database, String username, String password, String jdbcUrl) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
            this.jdbcUrl = jdbcUrl;
        }
    }
}
