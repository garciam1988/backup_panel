package app.coincidir.api.service.backups;

import app.coincidir.api.domain.backups.BackupRestoreHistory;
import app.coincidir.api.domain.backups.BackupRun;
import app.coincidir.api.domain.backups.BackupSettings;
import app.coincidir.api.repository.backups.BackupRestoreHistoryRepository;
import app.coincidir.api.repository.backups.BackupRunRepository;
import app.coincidir.api.repository.backups.BackupSettingsRepository;
import app.coincidir.api.service.AuthorizationCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class BackupsService {

    private final BackupRunRepository backupRunRepo;
    private final BackupSettingsRepository settingsRepo;
    private final BackupRestoreHistoryRepository restoreRepo;
    private final AuthorizationCodeService authorizationCodeService;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUser;

    @Value("${spring.datasource.password}")
    private String datasourcePass;

    @Value("${coincidir.backups.dump-dir:dumps}")
    private String dumpDir;

    private final AtomicBoolean runningDaily = new AtomicBoolean(false);

    public BackupSettings getOrCreateSettings() {
        return settingsRepo.findById(1L).orElseGet(() -> settingsRepo.save(
                BackupSettings.builder()
                        .id(1L)
                        .enabled(false)
                        .dailyTime("02:00")
                        .build()
        ));
    }

    public BackupSettings updateSettings(Boolean enabled, String dailyTime) {
        BackupSettings s = getOrCreateSettings();
        if (enabled != null) s.setEnabled(enabled);
        if (dailyTime != null && !dailyTime.isBlank()) {
            // valida formato HH:mm
            try {
                LocalTime.parse(dailyTime.trim());
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dailyTime inválido (HH:mm)");
            }
            s.setDailyTime(dailyTime.trim());
        }
        return settingsRepo.save(s);
    }

    public List<BackupRun> listBackupsFromDisk() {
        Path dir = ensureDumpDir();
        List<BackupRun> rows = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception ex) {
                            return 0;
                        }
                    })
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        long size;
                        Instant createdAt;
                        try {
                            size = Files.size(p);
                            createdAt = Files.getLastModifiedTime(p).toInstant();
                        } catch (Exception ex) {
                            size = 0;
                            createdAt = Instant.now();
                        }

                        BackupRun base = BackupRun.builder()
                                .fileName(fileName)
                                .filePath(p.toAbsolutePath().toString())
                                .sizeBytes(size)
                                .createdAt(createdAt)
                                .trigger("-")
                                .status("SUCCESS")
                                .message(null)
                                .build();

                        backupRunRepo.findTopByFileNameOrderByCreatedAtDesc(fileName).ifPresent(log -> {
                            base.setCreatedAt(log.getCreatedAt() != null ? log.getCreatedAt() : base.getCreatedAt());
                            base.setTrigger(log.getTrigger());
                            base.setStatus(log.getStatus());
                            base.setMessage(log.getMessage());
                        });

                        rows.add(base);
                    });
        } catch (Exception ex) {
            // si falla el listing, devolvemos lo que haya
        }
        return rows;
    }

    public BackupRun forceBackup(String trigger) {
        DbInfo db = parseDb();
        Path dir = ensureDumpDir();
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        String fileName = "coincidir_" + ts + ".sql";
        Path out = dir.resolve(fileName);

        List<String> cmd = new ArrayList<>();
        cmd.add("mysqldump");
        cmd.add("--host=" + db.host);
        cmd.add("--port=" + db.port);
        cmd.add("--user=" + db.user);
        // mysqldump acepta --password=xxx
        cmd.add("--password=" + db.pass);
        cmd.add("--routines");
        cmd.add("--events");
        cmd.add("--single-transaction");
        cmd.add("--skip-lock-tables");
        cmd.add("--databases");
        cmd.add(db.database);

        String err = null;
        String status = "SUCCESS";

        try {
            ExecResult r = exec(cmd, out.toFile(), null);
            if (r.exitCode != 0) {
                status = "FAILED";
                err = (r.stderr == null || r.stderr.isBlank()) ? "mysqldump falló" : r.stderr;
            }
        } catch (Exception ex) {
            status = "FAILED";
            err = ex.getMessage();
        }

        long size = 0;
        try {
            if (Files.exists(out)) size = Files.size(out);
        } catch (Exception ignored) {}

        BackupRun run = BackupRun.builder()
                .fileName(fileName)
                .filePath(out.toAbsolutePath().toString())
                .sizeBytes(size)
                .trigger(trigger == null ? "MANUAL" : trigger)
                .status(status)
                .message(err)
                .build();

        backupRunRepo.save(run);

        if (!"SUCCESS".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, err == null ? "Backup falló" : err);
        }

        return run;
    }

    public BackupRestoreHistory restore(String dumpFileName, String adminCode, String performedBy) {
        // valida código (si el user no es ADMIN)
        var vr = authorizationCodeService.validateAndGrant("ADMIN", adminCode);
        if (!vr.valid()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Código inválido");
        }

        Path dir = ensureDumpDir();
        Path dump = safeResolve(dir, dumpFileName);
        if (!Files.exists(dump) || !Files.isRegularFile(dump)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dump no encontrado");
        }

        BackupRestoreHistory hist = BackupRestoreHistory.builder()
                .startedAt(Instant.now())
                .dumpFileName(dumpFileName)
                .performedBy(performedBy)
                .status("RUNNING")
                .build();
        hist = restoreRepo.save(hist);

        DbInfo db = parseDb();

        String status = "SUCCESS";
        String message = null;
        try {
            // 1) DROP/CREATE
            List<String> dropCmd = new ArrayList<>();
            dropCmd.add("mysql");
            dropCmd.add("--host=" + db.host);
            dropCmd.add("--port=" + db.port);
            dropCmd.add("--user=" + db.user);
            dropCmd.add("--password=" + db.pass);
            dropCmd.add("-e");
            dropCmd.add("DROP DATABASE IF EXISTS `" + db.database + "`; CREATE DATABASE `" + db.database + "`;");

            ExecResult dropRes = exec(dropCmd, null, null);
            if (dropRes.exitCode != 0) {
                status = "FAILED";
                message = (dropRes.stderr == null || dropRes.stderr.isBlank()) ? "No se pudo recrear la base" : dropRes.stderr;
                throw new IllegalStateException(message);
            }

            // 2) IMPORT
            List<String> importCmd = new ArrayList<>();
            importCmd.add("mysql");
            importCmd.add("--host=" + db.host);
            importCmd.add("--port=" + db.port);
            importCmd.add("--user=" + db.user);
            importCmd.add("--password=" + db.pass);
            importCmd.add(db.database);

            ExecResult importRes = exec(importCmd, null, dump.toFile());
            if (importRes.exitCode != 0) {
                status = "FAILED";
                message = (importRes.stderr == null || importRes.stderr.isBlank()) ? "Import falló" : importRes.stderr;
                throw new IllegalStateException(message);
            }
        } catch (Exception ex) {
            if (message == null) message = ex.getMessage();
            if (status == null || "SUCCESS".equalsIgnoreCase(status)) status = "FAILED";
        }

        hist.setFinishedAt(Instant.now());
        hist.setStatus(status);
        hist.setMessage(message);
        restoreRepo.save(hist);

        if (!"SUCCESS".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message == null ? "Restore falló" : message);
        }

        return hist;
    }

    public List<BackupRestoreHistory> latestRestores(int limit) {
        int l = Math.max(1, Math.min(limit, 500));
        return restoreRepo.findLatest(org.springframework.data.domain.PageRequest.of(0, l));
    }

    public void tryRunDailyIfDue() {
        BackupSettings s = getOrCreateSettings();
        if (!s.isEnabled()) return;

        LocalTime target;
        try {
            target = LocalTime.parse(s.getDailyTime());
        } catch (Exception ex) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now().withSecond(0).withNano(0);

        if (!now.equals(target)) return;
        if (s.getLastDailyRun() != null && s.getLastDailyRun().isEqual(today)) return;

        if (!runningDaily.compareAndSet(false, true)) return;
        try {
            forceBackup("DAILY");
            s.setLastDailyRun(today);
            settingsRepo.save(s);
        } finally {
            runningDaily.set(false);
        }
    }

    private Path ensureDumpDir() {
        try {
            Path dir = Paths.get(dumpDir);
            if (!dir.isAbsolute()) {
                dir = Paths.get(System.getProperty("user.dir")).resolve(dir).normalize();
            }
            Files.createDirectories(dir);
            return dir;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo crear la carpeta dumps");
        }
    }

    private Path safeResolve(Path baseDir, String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (n.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dumpFileName requerido");
        Path p = baseDir.resolve(n).normalize();
        if (!p.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre de archivo inválido");
        }
        return p;
    }

    private record DbInfo(String host, int port, String database, String user, String pass) {}

    private DbInfo parseDb() {
        try {
            String u = datasourceUrl;
            if (u == null || !u.startsWith("jdbc:")) {
                throw new IllegalArgumentException("spring.datasource.url inválida");
            }
            String noJdbc = u.substring("jdbc:".length());
            java.net.URI uri = java.net.URI.create(noJdbc);
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() <= 0 ? 3306 : uri.getPort();
            String path = uri.getPath();
            String db = (path == null || path.isBlank()) ? "coincidir" : path.replaceFirst("^/", "");
            return new DbInfo(host, port, db, datasourceUser, datasourcePass);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer la config de DB");
        }
    }

    private static class ExecResult {
        final int exitCode;
        final String stderr;

        ExecResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr;
        }
    }

    private ExecResult exec(List<String> cmd, File outputFileOrNull, File stdinFileOrNull) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        if (outputFileOrNull != null) {
            pb.redirectOutput(outputFileOrNull);
        }
        if (stdinFileOrNull != null) {
            pb.redirectInput(stdinFileOrNull);
        }

        Process p = pb.start();

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (InputStream es = p.getErrorStream()) {
            es.transferTo(err);
        }
        int code = p.waitFor();

        String stderr = err.toString(StandardCharsets.UTF_8);
        // compacta
        if (stderr != null) stderr = stderr.trim();
        return new ExecResult(code, stderr);
    }
}
