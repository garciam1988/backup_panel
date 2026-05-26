package app.coincidir.api.errormonitor.controller;

import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.domain.logging.ClientLogEvent;
import app.coincidir.api.errormonitor.dto.*;
import app.coincidir.api.errormonitor.service.DataSourceHealthMonitor;
import app.coincidir.api.errormonitor.service.DataSourceHealthMonitor.HealthSnapshot;
import app.coincidir.api.errormonitor.service.ErrorMonitorService;
import app.coincidir.api.errormonitor.service.ErrorMonitorService.ErrorMonitorFilter;
import app.coincidir.api.repository.ClientLogEventRepository;
import app.coincidir.api.repository.PanelUserRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.lang.management.ManagementFactory;
import java.security.Principal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ErrorMonitorController — endpoints del módulo "Monitor de errores" en /admin.
 *
 * Acceso: SOLO rol DIOS (fullAccess=true). Cualquier otro usuario recibe 403
 * en todos los endpoints. El permiso se chequea en cada request, no en config
 * Spring Security, para que el código de filtrado sea explícito y auditable.
 *
 * Endpoints:
 *  GET    /api/admin/error-monitor                    — listado paginado con filtros
 *  GET    /api/admin/error-monitor/{id}               — detalle (con stack + breadcrumbs)
 *  GET    /api/admin/error-monitor/filters            — opciones de selects
 *  GET    /api/admin/error-monitor/stats              — agregaciones para dashboard
 *  GET    /api/admin/error-monitor/health             — estado del pool y APIs
 *  GET    /api/admin/error-monitor/export.csv         — export CSV con filtros
 *  POST   /api/admin/error-monitor/{id}/resolve       — marcar como resuelto
 *  POST   /api/admin/error-monitor/{id}/ignore        — marcar como ignorado
 *  POST   /api/admin/error-monitor/{id}/reopen        — reabrir
 *  POST   /api/admin/error-monitor/by-fingerprint/{fp}/resolve — resolver lote
 *  POST   /api/admin/error-monitor/cleanup            — borrar resueltos viejos
 *  POST   /api/admin/error-monitor/reset-reconnects   — reset contador health
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/error-monitor")
@RequiredArgsConstructor
public class ErrorMonitorController {

    private final ErrorMonitorService service;
    private final ClientLogEventRepository repo;
    private final PanelUserRepository userRepo;
    private final PermissionsService permissionsService;
    private final DataSourceHealthMonitor healthMonitor;

    // ── Listado paginado ──────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) Long   userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Principal principal) {

        requireDios(principal);

        ErrorMonitorFilter f = buildFilter(q, level, source, errorType, status, app,
                                            requestId, fingerprint, userId, userEmail, from, to);

        Page<ClientLogEvent> result = service.search(f, page, size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.getContent().stream().map(this::toList).collect(Collectors.toList()));
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        return out;
    }

    // ── Detalle ──────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ErrorMonitorDetailDto detail(@PathVariable Long id, Principal principal) {
        requireDios(principal);
        ClientLogEvent e = service.findById(id);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error no encontrado");
        return toDetail(e);
    }

    // ── Filtros disponibles (poblar selects) ──────────────────────────────

    @GetMapping("/filters")
    public ErrorMonitorFiltersDto filterOptions(Principal principal) {
        requireDios(principal);
        ErrorMonitorFiltersDto out = new ErrorMonitorFiltersDto();
        out.levels     = repo.findDistinctLevels();
        out.sources    = repo.findDistinctSources();
        out.errorTypes = repo.findDistinctErrorTypes();
        out.apps       = repo.findDistinctApps();
        return out;
    }

    // ── Stats / dashboard ─────────────────────────────────────────────────

    @GetMapping("/stats")
    public ErrorMonitorStatsDto stats(
            @RequestParam(required = false, defaultValue = "day") String bucket,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Principal principal) {

        requireDios(principal);

        // Por defecto: últimos 30 días
        Instant toIns = parseIsoOrNull(to);
        if (toIns == null) toIns = Instant.now();
        Instant fromIns = parseIsoOrNull(from);
        if (fromIns == null) fromIns = toIns.minus(30, java.time.temporal.ChronoUnit.DAYS);

        ErrorMonitorStatsDto stats = new ErrorMonitorStatsDto();
        stats.generatedAt = Instant.now();
        stats.rangeFrom = fromIns;
        stats.rangeTo = toIns;
        stats.bucket = bucket == null ? "day" : bucket;

        Instant now = Instant.now();
        Instant startToday   = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant startMinus7  = now.minus(7, java.time.temporal.ChronoUnit.DAYS);
        Instant startMinus30 = now.minus(30, java.time.temporal.ChronoUnit.DAYS);

        stats.kpis.errorsToday       = repo.countByServerTsGreaterThanEqual(startToday);
        stats.kpis.errorsLast7Days   = repo.countByServerTsGreaterThanEqual(startMinus7);
        stats.kpis.errorsLast30Days  = repo.countByServerTsGreaterThanEqual(startMinus30);

        // Stats por status en el rango
        for (Object[] row : repo.countByStatus(fromIns)) {
            String st = (String) row[0];
            Long cnt = ((Number) row[1]).longValue();
            stats.byStatus.put(st, cnt);
            switch (st) {
                case "open" -> stats.kpis.open = cnt;
                case "resolved" -> stats.kpis.resolved = cnt;
                case "ignored" -> stats.kpis.ignored = cnt;
                default -> { /* unknown bucket */ }
            }
        }
        long sumStatus = stats.kpis.open + stats.kpis.resolved + stats.kpis.ignored;
        stats.kpis.resolvedRate = sumStatus == 0 ? 0.0 :
                Math.round((stats.kpis.resolved * 1000.0 / sumStatus)) / 10.0;

        // Por tipo / nivel / source
        for (Object[] row : repo.countByErrorType(fromIns, toIns)) {
            stats.byType.put((String) row[0], ((Number) row[1]).longValue());
        }
        for (Object[] row : repo.countByLevel(fromIns, toIns)) {
            stats.byLevel.put((String) row[0], ((Number) row[1]).longValue());
        }
        for (Object[] row : repo.countBySource(fromIns, toIns)) {
            stats.bySource.put((String) row[0], ((Number) row[1]).longValue());
        }

        // Serie temporal por día con breakdown por tipo
        Map<LocalDate, ErrorMonitorStatsDto.TimeBucket> byDay = new LinkedHashMap<>();
        for (Object[] row : repo.countByDayAndType(fromIns, toIns)) {
            LocalDate day = sqlDateToLocalDate(row[0]);
            String type = (String) row[1];
            long cnt = ((Number) row[2]).longValue();
            ErrorMonitorStatsDto.TimeBucket b = byDay.computeIfAbsent(day, d -> {
                ErrorMonitorStatsDto.TimeBucket tb = new ErrorMonitorStatsDto.TimeBucket();
                tb.day = d;
                return tb;
            });
            b.byType.put(type, cnt);
            b.total += cnt;
        }
        stats.timeSeries = new ArrayList<>(byDay.values());

        // Top recurring (top 10)
        stats.topRecurring = new ArrayList<>();
        for (Object[] row : repo.findTopRecurring(fromIns, toIns, 10)) {
            ErrorMonitorStatsDto.TopError t = new ErrorMonitorStatsDto.TopError();
            t.fingerprint = (String) row[0];
            t.shortDesc   = (String) row[1];
            t.errorType   = (String) row[2];
            t.level       = (String) row[3];
            t.count       = ((Number) row[4]).longValue();
            t.lastSeen    = toInstant(row[5]);
            t.firstSeen   = toInstant(row[6]);
            stats.topRecurring.add(t);
        }

        // Reconexiones (calculadas desde la tabla de errores)
        Instant minus24h = now.minus(24, java.time.temporal.ChronoUnit.HOURS);
        Instant minus7d = now.minus(7, java.time.temporal.ChronoUnit.DAYS);
        stats.dbReconnects24h    = repo.countDbReconnects(minus24h);
        stats.dbReconnects7d     = repo.countDbReconnects(minus7d);
        stats.dbErrors24h        = repo.countDatabaseErrors(minus24h);
        stats.anthropicErrors24h = repo.countAnthropicErrors(minus24h);

        return stats;
    }

    // ── Health snapshot ───────────────────────────────────────────────────

    @GetMapping("/health")
    public ErrorMonitorHealthDto health(Principal principal) {
        requireDios(principal);
        HealthSnapshot snap = healthMonitor.getSnapshot();
        ErrorMonitorHealthDto h = new ErrorMonitorHealthDto();
        h.generatedAt = Instant.now();
        h.backendUptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;

        if (snap != null) {
            h.poolName          = snap.poolName;
            h.totalConnections  = snap.totalConnections;
            h.activeConnections = snap.activeConnections;
            h.idleConnections   = snap.idleConnections;
            h.threadsAwaiting   = snap.threadsAwaiting;
            h.maxPoolSize       = snap.maxPoolSize;
            h.minIdle           = snap.minIdle;
            h.connectionTimeout = snap.connectionTimeout;
            h.idleTimeout       = snap.idleTimeout;
            h.maxLifetime       = snap.maxLifetime;
            h.dbReconnectsTotal = snap.reconnects;
            h.lastSuccessfulPing = snap.lastSuccessfulPing;
            h.lastFailedPing = snap.lastFailedPing;
            h.lastPingError = snap.lastPingError;
        }

        Instant minus24h = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        Instant minus7d = Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        h.dbReconnectsLast24h = repo.countDbReconnects(minus24h);
        h.dbErrorsLast24h     = repo.countDatabaseErrors(minus24h);
        h.dbErrorsLast7d      = repo.countDatabaseErrors(minus7d);
        h.anthropicErrorsLast24h = repo.countAnthropicErrors(minus24h);
        h.anthropicErrorsLast7d  = repo.countAnthropicErrors(minus7d);

        // Heurística de status:
        //   DB: si el último ping OK fue hace <2min Y no hay fallido más reciente → OK
        //       si el último fallido es más reciente que el OK → DOWN
        //       si último OK fue hace 2-10min y hay errores recientes → DEGRADED
        Instant lastOk = h.lastSuccessfulPing;
        Instant lastFail = h.lastFailedPing;
        if (lastFail != null && (lastOk == null || lastFail.isAfter(lastOk))) {
            h.dbStatus = "DOWN";
        } else if (lastOk != null && Duration.between(lastOk, Instant.now()).getSeconds() > 600) {
            h.dbStatus = "DEGRADED";
        } else {
            h.dbStatus = "OK";
        }

        // Anthropic: si hay > 5 errores en 24h → DEGRADED, sino OK
        h.anthropicStatus = (h.anthropicErrorsLast24h != null && h.anthropicErrorsLast24h > 5)
                ? "DEGRADED" : "OK";

        return h;
    }

    // ── Export CSV ────────────────────────────────────────────────────────

    @GetMapping("/export.csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "5000") int maxRows,
            Principal principal) {

        requireDios(principal);

        ErrorMonitorFilter f = buildFilter(q, level, source, errorType, status, app,
                                            requestId, fingerprint, userId, userEmail, from, to);

        // Para export, paginamos manualmente — máximo 5000 filas para no
        // bloquear el thread del Tomcat con un response gigantesco.
        int pageSize = 200;
        int totalCap = Math.min(maxRows, 10000);
        StringBuilder sb = new StringBuilder();
        sb.append("fecha,nivel,source,tipo,descripcion_corta,usuario,rol,http,status,fingerprint,pathname,request_id,exception\n");

        int written = 0;
        int page = 0;
        while (written < totalCap) {
            Page<ClientLogEvent> p = service.search(f, page, pageSize);
            if (p.isEmpty()) break;
            for (ClientLogEvent e : p.getContent()) {
                if (written >= totalCap) break;
                sb.append(csv(e.getServerTs() != null ? e.getServerTs().toString() : "")).append(',');
                sb.append(csv(e.getLevel())).append(',');
                sb.append(csv(e.getSource())).append(',');
                sb.append(csv(e.getErrorType())).append(',');
                sb.append(csv(e.getShortDesc())).append(',');
                sb.append(csv(e.getUserEmail())).append(',');
                sb.append(csv(e.getUserRole())).append(',');
                sb.append(csv(e.getHttpStatus() != null ? e.getHttpStatus().toString() : "")).append(',');
                sb.append(csv(e.getStatus())).append(',');
                sb.append(csv(e.getFingerprint())).append(',');
                sb.append(csv(e.getPathname())).append(',');
                sb.append(csv(e.getRequestId())).append(',');
                sb.append(csv(e.getExceptionClass())).append('\n');
                written++;
            }
            if (!p.hasNext()) break;
            page++;
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"error-monitor.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(sb.toString());
    }

    // ── Acciones de estado ────────────────────────────────────────────────

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body,
                                       Principal principal) {
        requireDios(principal);
        String note = body == null ? null : body.get("note");
        int n = service.markResolved(id, principal.getName(), note);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error no encontrado");
        return Map.of("ok", true, "updated", n);
    }

    @PostMapping("/{id}/ignore")
    public Map<String, Object> ignore(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, String> body,
                                      Principal principal) {
        requireDios(principal);
        String note = body == null ? null : body.get("note");
        int n = service.markIgnored(id, principal.getName(), note);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error no encontrado");
        return Map.of("ok", true, "updated", n);
    }

    @PostMapping("/{id}/reopen")
    public Map<String, Object> reopen(@PathVariable Long id, Principal principal) {
        requireDios(principal);
        int n = service.reopen(id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error no encontrado");
        return Map.of("ok", true, "updated", n);
    }

    @PostMapping("/by-fingerprint/{fingerprint}/resolve")
    public Map<String, Object> resolveAllByFp(@PathVariable String fingerprint,
                                              @RequestBody(required = false) Map<String, String> body,
                                              Principal principal) {
        requireDios(principal);
        String note = body == null ? null : body.get("note");
        int n = service.resolveAllByFingerprint(fingerprint, principal.getName(), note);
        return Map.of("ok", true, "updated", n);
    }

    // ── Maintenance ──────────────────────────────────────────────────────

    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(
            @RequestParam(defaultValue = "30") int olderThanDays,
            @RequestParam(defaultValue = "true") boolean onlyResolved,
            Principal principal) {
        requireDios(principal);
        int days = Math.max(1, olderThanDays);
        int n = onlyResolved ? service.deleteResolvedOlderThan(days) : service.deleteOlderThan(days);
        return Map.of("ok", true, "deleted", n, "olderThanDays", days, "onlyResolved", onlyResolved);
    }

    @PostMapping("/reset-reconnects")
    public Map<String, Object> resetReconnects(Principal principal) {
        requireDios(principal);
        healthMonitor.resetReconnectCounter();
        return Map.of("ok", true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Verifica que el usuario actual sea DIOS (fullAccess=true). Lanza 403
     * si no. La sección "Monitor de errores" es exclusiva — no se otorga
     * por adminSection porque sería peligroso (expone info sensible: stack
     * traces, IPs, paths internos, etc.).
     */
    private void requireDios(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        PanelUser u = userRepo.findByUsername(principal.getName()).orElse(null);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        EffectivePermissions perms = permissionsService.resolve(u);
        if (!perms.fullAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo DIOS puede acceder al monitor de errores.");
        }
    }

    private ErrorMonitorFilter buildFilter(String q, String level, String source,
                                            String errorType, String status, String app,
                                            String requestId, String fingerprint,
                                            Long userId, String userEmail,
                                            String from, String to) {
        ErrorMonitorFilter f = new ErrorMonitorFilter();
        f.q = blankToNull(q);
        f.level = blankToNull(level);
        f.source = blankToNull(source);
        f.errorType = blankToNull(errorType);
        f.status = blankToNull(status);
        f.app = blankToNull(app);
        f.requestId = blankToNull(requestId);
        f.fingerprint = blankToNull(fingerprint);
        f.userId = userId;
        f.userEmail = blankToNull(userEmail);
        f.from = parseIsoOrNull(from);
        f.to   = parseIsoOrNull(to);
        return f;
    }

    private ErrorMonitorListDto toList(ClientLogEvent e) {
        ErrorMonitorListDto d = new ErrorMonitorListDto();
        d.id = e.getId();
        d.serverTs = e.getServerTs();
        d.clientTs = e.getClientTs();
        d.level = e.getLevel();
        d.source = e.getSource();
        d.errorType = e.getErrorType();
        d.shortDesc = e.getShortDesc();
        d.status = e.getStatus();
        d.fingerprint = e.getFingerprint();
        d.userEmail = e.getUserEmail();
        d.userRole = e.getUserRole();
        d.pathname = e.getPathname();
        d.app = e.getApp();
        d.requestId = e.getRequestId();
        d.httpStatus = e.getHttpStatus();
        d.hasDetail = e.getDetail() != null && !e.getDetail().isBlank();
        d.hasBreadcrumbs = e.getBreadcrumbsJson() != null && !e.getBreadcrumbsJson().isBlank();
        return d;
    }

    private ErrorMonitorDetailDto toDetail(ClientLogEvent e) {
        ErrorMonitorDetailDto d = new ErrorMonitorDetailDto();
        d.id = e.getId();
        d.serverTs = e.getServerTs();
        d.clientTs = e.getClientTs();
        d.level = e.getLevel();
        d.source = e.getSource();
        d.category = e.getCategory();
        d.errorType = e.getErrorType();
        d.shortDesc = e.getShortDesc();
        d.message = e.getMessage();
        d.detail = e.getDetail();
        d.recommendation = e.getRecommendation();
        d.previousAction = e.getPreviousAction();
        d.breadcrumbsJson = e.getBreadcrumbsJson();
        d.dataJson = e.getDataJson();
        d.status = e.getStatus();
        d.fingerprint = e.getFingerprint();
        d.occurrenceCount = e.getOccurrenceCount();
        d.httpStatus = e.getHttpStatus();
        d.exceptionClass = e.getExceptionClass();
        d.resolvedBy = e.getResolvedBy();
        d.resolvedAt = e.getResolvedAt();
        d.resolutionNote = e.getResolutionNote();
        d.userId = e.getUserId();
        d.userEmail = e.getUserEmail();
        d.userRole = e.getUserRole();
        d.url = e.getUrl();
        d.pathname = e.getPathname();
        d.userAgent = e.getUserAgent();
        d.platform = e.getPlatform();
        d.ip = e.getIp();
        d.sessionId = e.getSessionId();
        d.requestId = e.getRequestId();
        d.app = e.getApp();
        d.env = e.getEnv();
        return d;
    }

    private Instant parseIsoOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
            try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) {}
            return null;
        }
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String csv(String s) {
        if (s == null) return "";
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String esc = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + esc + "\"" : esc;
    }

    private LocalDate sqlDateToLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        return LocalDate.parse(o.toString());
    }

    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant in) return in;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.util.Date d) return d.toInstant();
        try { return Instant.parse(o.toString()); } catch (Exception e) { return null; }
    }
}
