package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotQueryLog;
import app.coincidir.api.botplatform.repository.BotQueryLogRepository;
import app.coincidir.api.botplatform.service.SqlExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SqlExecutionController — endpoints admin para probar y auditar el SQL
 * libre que el bot puede ejecutar contra conectores.
 *
 *   POST /api/admin/bot-connectors/{id}/execute-sql
 *        → ejecuta un SQL de prueba (valida, fuerza limit, log, devuelve filas)
 *
 *   GET  /api/admin/bot-connectors/{id}/query-log?limit=20
 *        → últimos logs de queries del bot sobre este conector
 *
 * Sirve para:
 *   - Que el admin pruebe queries antes de habilitar SQL libre en producción.
 *   - Que el admin/cliente vea qué queries armó el bot ante una pregunta.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-connectors")
@RequiredArgsConstructor
public class SqlExecutionController {

    private final SqlExecutionService sqlExec;
    private final BotQueryLogRepository logRepo;

    /** Cuerpo del request: solo el SQL a probar. */
    public record ExecRequest(String sql) {}

    @PostMapping("/{id}/execute-sql")
    public SqlExecutionService.ExecResult execute(@PathVariable Long id,
                                                  @RequestBody ExecRequest body) {
        String sql = body == null ? null : body.sql();
        return sqlExec.execute(id, sql, /*sessionId*/ null, /*userQuestion*/ "admin-test");
    }

    /**
     * Devuelve el log paginado de queries SQL ejecutadas por el bot contra
     * este conector. Las más recientes primero.
     *
     * Parámetros:
     *   page → 0-indexed, default 0
     *   size → filas por página, default 20, cap 200
     *
     * Respuesta:
     *   {
     *     rows: [...],              ← las queries del log
     *     totalElements: 1234,      ← total para mostrar "Página X de Y"
     *     totalPages: 62,
     *     page: 0,                  ← echo para que el frontend confirme
     *     size: 20
     *   }
     *
     * Mantengo soporte del parámetro legacy `limit` (sin page) para no
     * romper código viejo que lo use; si viene `limit` y no `page`,
     * trato `limit` como tamaño con page=0.
     */
    @GetMapping("/{id}/query-log")
    public Map<String, Object> queryLog(@PathVariable Long id,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) Integer limit) {
        int effectiveSize = size != null ? size : (limit != null ? limit : 20);
        int safeSize = Math.max(1, Math.min(effectiveSize, 200));
        int safePage = Math.max(0, page != null ? page : 0);

        var pageObj = logRepo.findByConnectorIdOrderByCreatedAtDesc(
                id, PageRequest.of(safePage, safeSize));

        List<Map<String, Object>> rows = pageObj.getContent().stream()
                .map(this::toDto)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totalElements", pageObj.getTotalElements());
        out.put("totalPages", pageObj.getTotalPages());
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    private Map<String, Object> toDto(BotQueryLog l) {
        // Uso LinkedHashMap en vez de Map.of para preservar el orden de los
        // campos (más legible en DevTools) y permitir nulls si hiciera falta.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("createdAt", l.getCreatedAt());
        m.put("sessionId", l.getSessionId() == null ? "" : l.getSessionId());
        m.put("userQuestion", l.getUserQuestion() == null ? "" : l.getUserQuestion());
        m.put("sqlText", l.getSqlText() == null ? "" : l.getSqlText());
        m.put("status", l.getStatus() == null ? "" : l.getStatus());
        m.put("errorMessage", l.getErrorMessage() == null ? "" : l.getErrorMessage());
        m.put("rowsReturned", l.getRowsReturned() == null ? 0 : l.getRowsReturned());
        m.put("durationMs", l.getDurationMs() == null ? 0L : l.getDurationMs());
        m.put("resultSizeBytes", l.getResultSizeBytes() == null ? 0 : l.getResultSizeBytes());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Métricas agregadas (Fase 4)
    // ─────────────────────────────────────────────────────────────────────
    //
    //   GET /api/admin/bot-connectors/{id}/metrics?days=7
    //
    // Calcula y devuelve un resumen del uso del SQL libre contra el conector
    // en los últimos N días. Pensado para alimentar un dashboard simple en
    // el admin (cards de KPI + gráfico de barras por día + top SQLs).
    //
    // Por qué no usar el log directo desde el frontend: con miles de filas,
    // descargar todo y agregar en el browser sería lento y caro. El backend
    // agrega con SQL y devuelve ~10KB sin importar el volumen subyacente.
    //
    // Diseño de la respuesta: planar y predecible, sin nesting innecesario,
    // para que el frontend lo pueda renderizar con poca lógica de transform.

    @GetMapping("/{id}/metrics")
    public Map<String, Object> metrics(@PathVariable Long id,
                                       @RequestParam(defaultValue = "7") int days) {
        // Cap razonable: 1 día como mínimo, 365 como máximo. Si pasan algo
        // raro, normalizamos en silencio en vez de fallar (UX > strictness).
        int safeDays = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectorId", id);
        out.put("days", safeDays);
        out.put("since", since);

        // 1) Agregados generales
        // El query devuelve una List<Object[]> con UNA sola fila:
        //   [count, avgDur, maxDur, totalRows, totalBytes]
        //
        // Antes usábamos retorno Object[] directo pero JPA con MySQL/Hibernate 6+
        // a veces desempaca mal el primer elemento devolviendo 0 (bug observado:
        // totalQueries=0 pese a tener 15 filas reales). Con List<Object[]> el
        // comportamiento es predecible en todas las versiones: get(0) es la
        // fila completa.
        List<Object[]> aggList = logRepo.aggregateMetrics(id, since);
        Object[] agg = (aggList != null && !aggList.isEmpty()) ? aggList.get(0) : null;
        Map<String, Object> totals = new LinkedHashMap<>();
        if (agg != null && agg.length >= 5) {
            long totalCount = agg[0] != null ? ((Number) agg[0]).longValue() : 0L;
            totals.put("totalQueries", totalCount);
            totals.put("avgDurationMs",
                    agg[1] != null ? Math.round(((Number) agg[1]).doubleValue()) : 0L);
            totals.put("maxDurationMs", agg[2] != null ? ((Number) agg[2]).longValue() : 0L);
            totals.put("totalRows",     agg[3] != null ? ((Number) agg[3]).longValue() : 0L);
            totals.put("totalBytes",    agg[4] != null ? ((Number) agg[4]).longValue() : 0L);
        } else {
            totals.put("totalQueries", 0L);
            totals.put("avgDurationMs", 0L);
            totals.put("maxDurationMs", 0L);
            totals.put("totalRows", 0L);
            totals.put("totalBytes", 0L);
        }
        out.put("totals", totals);

        // 2) Breakdown por status
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : logRepo.statusBreakdown(id, since)) {
            String status = (String) row[0];
            long count = ((Number) row[1]).longValue();
            byStatus.put(status == null ? "UNKNOWN" : status, count);
        }
        out.put("byStatus", byStatus);

        // Success rate = (OK + TRUNCATED) / total. Útil para card de KPI.
        long total = (Long) totals.get("totalQueries");
        long okish = byStatus.getOrDefault("OK", 0L)
                   + byStatus.getOrDefault("TRUNCATED", 0L);
        double successRate = total == 0 ? 0.0 : (double) okish / (double) total;
        out.put("successRate", successRate);

        // 3) Top SQLs (5 más ejecutadas)
        List<Map<String, Object>> topSqls = new ArrayList<>();
        for (Object[] row : logRepo.topSqls(id, since, PageRequest.of(0, 5))) {
            Map<String, Object> e = new LinkedHashMap<>();
            String sql = (String) row[0];
            // Trunco a 300 chars para no inflar el JSON si alguien armó un
            // SQL gigante. El frontend de todas formas trunca al renderizar.
            e.put("sql", sql == null ? "" : (sql.length() > 300 ? sql.substring(0, 300) : sql));
            e.put("count", ((Number) row[1]).longValue());
            topSqls.add(e);
        }
        out.put("topSqls", topSqls);

        // 4) Serie diaria — para el gráfico de barras por día
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object[] row : logRepo.dailySeries(id, since)) {
            Map<String, Object> e = new LinkedHashMap<>();
            // row[0] es java.sql.Date (DATE() en MySQL). Lo serializamos
            // como string ISO yyyy-MM-dd que es lo que el frontend espera.
            Object day = row[0];
            e.put("day", day == null ? null : day.toString());
            e.put("total", ((Number) row[1]).longValue());
            e.put("ok",    ((Number) row[2]).longValue());
            e.put("error", ((Number) row[3]).longValue());
            daily.add(e);
        }
        out.put("daily", daily);

        return out;
    }
}
