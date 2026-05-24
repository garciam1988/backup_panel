package app.coincidir.api.apiusage.service;

import app.coincidir.api.apiusage.repository.ApiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UsageStatsService — consultas agregadas para el dashboard. Separado del
 * ApiUsageService de escritura para mantener responsabilidades claras.
 *
 * Todas las queries usan rangos [from, to) sobre createdAt. Las "fechas"
 * en sentido humano (hoy/mes/año) se calculan con ZoneId del sistema (que
 * en Railway debería estar configurado a America/Argentina/Buenos_Aires
 * para que "hoy" coincida con el día del cliente).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageStatsService {

    private final ApiUsageLogRepository logRepo;
    private final app.coincidir.api.repository.ConversationLogRepository conversationRepo;
    private final app.coincidir.api.repository.BotConfigRepository botConfigRepo;

    /** Resumen para las 3 tarjetas: hoy, este mes, este año. */
    @Transactional(readOnly = true)
    public SummaryDto summary() {
        ZoneId tz = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(tz);

        Instant todayStart  = now.toLocalDate().atStartOfDay(tz).toInstant();
        Instant monthStart  = now.toLocalDate().withDayOfMonth(1).atStartOfDay(tz).toInstant();
        Instant yearStart   = now.toLocalDate().withDayOfYear(1).atStartOfDay(tz).toInstant();
        Instant nowInstant  = now.toInstant();

        SummaryDto s = new SummaryDto();
        s.todayUsd  = logRepo.sumCostBetween(todayStart, nowInstant);
        s.monthUsd  = logRepo.sumCostBetween(monthStart, nowInstant);
        s.yearUsd   = logRepo.sumCostBetween(yearStart,  nowInstant);
        s.todayCalls = logRepo.countByCreatedAtBetween(todayStart, nowInstant);
        s.monthCalls = logRepo.countByCreatedAtBetween(monthStart, nowInstant);
        s.yearCalls  = logRepo.countByCreatedAtBetween(yearStart,  nowInstant);

        // Breakdown por provider (mes en curso)
        s.byProvider = new ArrayList<>();
        for (Object[] row : logRepo.sumCostByProvider(monthStart, nowInstant)) {
            BreakdownRow r = new BreakdownRow();
            r.key = (String) row[0];
            r.costUsd = (BigDecimal) row[1];
            r.calls = (Long) row[2];
            s.byProvider.add(r);
        }

        // Breakdown por feature (mes en curso)
        s.byFeature = new ArrayList<>();
        for (Object[] row : logRepo.sumCostByFeature(monthStart, nowInstant)) {
            BreakdownRow r = new BreakdownRow();
            r.key = (String) row[0];
            r.costUsd = (BigDecimal) row[1];
            r.calls = (Long) row[2];
            s.byFeature.add(r);
        }

        // Breakdown por modelo (mes en curso)
        s.byModel = new ArrayList<>();
        for (Object[] row : logRepo.sumCostByModel(monthStart, nowInstant)) {
            ModelRow r = new ModelRow();
            r.provider = (String) row[0];
            r.model = (String) row[1];
            r.costUsd = (BigDecimal) row[2];
            r.calls = (Long) row[3];
            s.byModel.add(r);
        }

        // Tokens del mes (informativo)
        Object[] tokens = logRepo.sumTokensBetween(monthStart, nowInstant);
        if (tokens != null && tokens.length >= 4) {
            s.monthInputTokens = ((Number) tokens[0]).longValue();
            s.monthOutputTokens = ((Number) tokens[1]).longValue();
            s.monthCacheReadTokens = ((Number) tokens[2]).longValue();
            s.monthCacheWriteTokens = ((Number) tokens[3]).longValue();
        }
        return s;
    }

    /**
     * Datos para el gráfico de línea — costo diario de los últimos N días.
     * Devuelve una lista de pares (date, costUsd, calls) en orden cronológico.
     * Si un día no tuvo llamadas, igual aparece con cost=0 (para que el
     * gráfico no salte fechas).
     */
    @Transactional(readOnly = true)
    public List<TimelinePoint> timeline(int days) {
        if (days < 1) days = 30;
        if (days > 90) days = 90; // cap defensivo

        ZoneId tz = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(tz);
        List<TimelinePoint> result = new ArrayList<>(days);

        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Instant start = d.atStartOfDay(tz).toInstant();
            Instant end = d.plusDays(1).atStartOfDay(tz).toInstant();

            TimelinePoint p = new TimelinePoint();
            p.date = d.toString();
            p.costUsd = logRepo.sumCostBetween(start, end);
            p.calls = logRepo.countByCreatedAtBetween(start, end);
            result.add(p);
        }
        return result;
    }

    /** Top N sesiones por costo (mes en curso por default). */
    @Transactional(readOnly = true)
    public List<TopSessionRow> topSessions(int limit) {
        if (limit < 1) limit = 10;
        if (limit > 50) limit = 50;

        ZoneId tz = ZoneId.systemDefault();
        Instant monthStart = LocalDate.now(tz).withDayOfMonth(1).atStartOfDay(tz).toInstant();
        Instant nowInstant = Instant.now();

        List<TopSessionRow> rows = new ArrayList<>();
        for (Object[] row : logRepo.topSessionsByCost(monthStart, nowInstant, PageRequest.of(0, limit))) {
            TopSessionRow r = new TopSessionRow();
            r.sessionId = (String) row[0];
            r.costUsd = (BigDecimal) row[1];
            r.calls = (Long) row[2];
            r.firstAt = (Instant) row[3];
            r.lastAt = (Instant) row[4];
            rows.add(r);
        }
        return rows;
    }

    // ──────────────────────────────────────────────────────────────────
    //  botStats — Estadísticas focalizadas en el COSTO DEL BOT
    //
    //  Endpoint nuevo (vs. summary() que es legacy y general). Esta query
    //  está pensada para responder las preguntas operativas del operador:
    //    "¿cuánto me cuesta cada mensaje del bot?"
    //    "¿cuánto me cuesta cada conversación?"
    //    "¿el modelo elegido me sirve o estoy gastando de más?"
    //    "¿cuántas conversaciones simples vs complejas tuve?"
    //
    //  A diferencia de summary() (que mide TODO el costo de la API
    //  Anthropic incluyendo extract_client_data, transcript, etc), botStats
    //  filtra por feature='chat' para que las métricas reflejen sólo el
    //  costo del bot conversacional. Eso es lo que el operador realmente
    //  necesita para tomar decisiones de routing.
    // ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BotStatsDto botStats(int days) {
        if (days != 7 && days != 30) days = 7; // soportamos solo 7 y 30 por ahora
        ZoneId tz = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(tz);
        Instant to = now.toInstant();
        Instant from = now.minusDays(days).toInstant();

        BotStatsDto s = new BotStatsDto();
        s.windowDays = days;
        s.windowStart = from;

        // ── Config activa del LLM ─────────────────────────────────────
        // Leemos bot_config singleton para mostrar qué modo está corriendo.
        // Si no existe el registro, default sonnet_only (consistente con el
        // comportamiento del AiModelRouter).
        try {
            s.aiRoutingMode = botConfigRepo.findById(1L)
                    .map(app.coincidir.api.domain.BotConfig::getAiRoutingMode)
                    .orElse("sonnet_only");
            if (s.aiRoutingMode == null || s.aiRoutingMode.isBlank()) {
                s.aiRoutingMode = "sonnet_only";
            }
        } catch (Exception e) {
            log.warn("[botStats] no pude leer bot_config.ai_routing_mode: {}", e.getMessage());
            s.aiRoutingMode = "sonnet_only";
        }

        // ── Totales de chat ────────────────────────────────────────────
        Object[] totals = logRepo.chatTotals(from, to);
        // El resultado viene wrapped en otro Object[] cuando es una sola fila —
        // dependiendo de Hibernate. Defensivamente unwrappeamos.
        Object[] row = (totals != null && totals.length == 1 && totals[0] instanceof Object[])
                ? (Object[]) totals[0] : totals;
        if (row == null) row = new Object[]{BigDecimal.ZERO, 0L, 0L, 0L, 0L, 0L};

        s.totalCost = toBigDecimal(row[0]);
        s.totalMessages = toLong(row[1]);
        long input = toLong(row[2]);
        long output = toLong(row[3]);
        long cacheRead = toLong(row[4]);
        long cacheWrite = toLong(row[5]);

        // ── Sesiones distintas ─────────────────────────────────────────
        s.totalSessions = logRepo.countDistinctChatSessions(from, to);

        // ── Métricas promediadas ───────────────────────────────────────
        // Costo por mensaje y por sesión: defensivo con divisiones por cero.
        s.avgCostPerMessage = s.totalMessages > 0
                ? s.totalCost.divide(BigDecimal.valueOf(s.totalMessages), 6, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        s.avgCostPerSession = s.totalSessions > 0
                ? s.totalCost.divide(BigDecimal.valueOf(s.totalSessions), 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // ── Cache hit rate ─────────────────────────────────────────────
        // cache hit % = cacheRead / (input + cacheRead) * 100
        // Si input es 0 (todo viene del cache) o cacheRead es 0, el cálculo
        // es trivial. Defensivo con división por cero.
        long totalInputForCache = input + cacheRead;
        s.cacheHitPct = totalInputForCache > 0
                ? Math.round((cacheRead * 1000.0) / totalInputForCache) / 10.0
                : 0.0;

        // ── Métricas desde conversation_log ────────────────────────────
        // Duración promedio y mensajes promedio vienen del log de conversaciones
        // (que tiene el dato calculado, vs. tener que reconstruirlo desde
        // api_usage_log).
        try {
            Object[] convRow = conversationRepo.conversationStats(from, to);
            Object[] convUnwrapped = (convRow != null && convRow.length == 1 && convRow[0] instanceof Object[])
                    ? (Object[]) convRow[0] : convRow;
            if (convUnwrapped != null && convUnwrapped.length >= 3) {
                Long conversationCount = toLong(convUnwrapped[0]);
                Double avgDuration = convUnwrapped[1] != null
                        ? ((Number) convUnwrapped[1]).doubleValue() : 0.0;
                Double avgMessages = convUnwrapped[2] != null
                        ? ((Number) convUnwrapped[2]).doubleValue() : 0.0;
                // Si conversation_log no tiene datos (caso edge: clientes con
                // poco volumen), usamos totalSessions de api_usage_log como
                // fallback para que el dashboard no muestre 0 cuando hay datos.
                if (conversationCount == 0) {
                    s.avgDurationMinutes = 0.0;
                    s.avgMessagesPerSession = s.totalSessions > 0
                            ? (double) s.totalMessages / s.totalSessions : 0.0;
                } else {
                    s.avgDurationMinutes = Math.round(avgDuration * 10) / 10.0;
                    s.avgMessagesPerSession = Math.round(avgMessages * 10) / 10.0;
                }
            }
        } catch (Exception e) {
            log.warn("[botStats] error leyendo conversation_log: {}", e.getMessage());
            s.avgDurationMinutes = 0.0;
            s.avgMessagesPerSession = s.totalSessions > 0
                    ? (double) s.totalMessages / s.totalSessions : 0.0;
        }

        // ── Distribución por modelo ────────────────────────────────────
        // Solo relevante si smart_routing está activo y hay >1 modelo en uso.
        s.modelDistribution = new ArrayList<>();
        for (Object[] r : logRepo.chatModelDistribution(from, to)) {
            ModelDistRow m = new ModelDistRow();
            m.model = (String) r[0];
            m.costUsd = toBigDecimal(r[1]);
            m.calls = toLong(r[2]);
            m.sessions = toLong(r[3]);
            // Porcentaje del costo total (no del input).
            if (s.totalCost.compareTo(BigDecimal.ZERO) > 0) {
                m.pctOfCost = m.costUsd
                        .multiply(BigDecimal.valueOf(100))
                        .divide(s.totalCost, 1, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
            }
            s.modelDistribution.add(m);
        }

        // ── Buckets de complejidad ─────────────────────────────────────
        // Clasificación por turnos (proxy operativo, no semántico):
        //   - Simple:   1-4 turnos  (consultas rápidas, reservas express)
        //   - Media:    5-10 turnos (reservas estándar con preguntas)
        //   - Compleja: 11+ turnos  (modificaciones, casos confusos)
        s.complexity = new ComplexityBuckets();
        for (Object[] r : logRepo.turnsPerSession(from, to)) {
            long turns = toLong(r[1]);
            if (turns <= 4)       s.complexity.simple++;
            else if (turns <= 10) s.complexity.medium++;
            else                  s.complexity.complex++;
        }

        // ── Timeline diario ────────────────────────────────────────────
        s.daily = new ArrayList<>();
        for (Object[] r : logRepo.chatDailyTimeline(from, to)) {
            DailyPoint p = new DailyPoint();
            // FUNCTION('DATE') devuelve java.sql.Date — lo convertimos a string
            // ISO simple para que el frontend no tenga que parsear.
            p.date = r[0] != null ? r[0].toString() : "";
            p.costUsd = toBigDecimal(r[1]);
            p.calls = toLong(r[2]);
            s.daily.add(p);
        }

        // ── Top sesiones ───────────────────────────────────────────────
        s.topSessions = new ArrayList<>();
        for (Object[] r : logRepo.chatTopSessions(from, to, PageRequest.of(0, 5))) {
            TopSessionRow t = new TopSessionRow();
            t.sessionId = (String) r[0];
            t.costUsd = toBigDecimal(r[1]);
            t.calls = toLong(r[2]);
            t.firstAt = (Instant) r[3];
            t.lastAt = (Instant) r[4];
            s.topSessions.add(t);
        }

        // ── Recomendaciones automáticas ────────────────────────────────
        // Texto generado en el backend según los números. Más simple y
        // consistente que dejarle al frontend interpretar umbrales.
        s.recommendations = buildRecommendations(s);

        return s;
    }

    /**
     * Genera sugerencias accionables según las métricas observadas.
     * Reglas heurísticas — fácil de extender sumando más checks.
     */
    private List<String> buildRecommendations(BotStatsDto s) {
        List<String> recs = new ArrayList<>();

        // Sin datos → nada que recomendar
        if (s.totalSessions == 0) {
            recs.add("Todavía no hay suficientes conversaciones en esta ventana para sugerir cambios.");
            return recs;
        }

        // Cache hit bajo → algo está mal con el caching
        if (s.cacheHitPct < 50.0 && s.totalMessages > 10) {
            recs.add("Cache hit rate es " + s.cacheHitPct + "% (esperado: >85%). " +
                "Revisar si el system prompt cambia entre turnos, o si las conversaciones son " +
                "tan largas que el cache de 5min expira mid-charla.");
        }

        // Sonnet only y bajo costo → considerar smart_routing
        if ("sonnet_only".equals(s.aiRoutingMode) && s.totalSessions >= 20) {
            BigDecimal estimatedSavings = s.totalCost
                    .multiply(new BigDecimal("0.35"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            recs.add("Estás usando Sonnet para todas las conversaciones. " +
                "Cambiar a 'Routing inteligente' podría ahorrar ~$" + estimatedSavings +
                " en los próximos " + s.windowDays + " días, con baja regresión en calidad. " +
                "Probalo en LLM → 'Routing inteligente'.");
        }

        // Smart routing con >70% Haiku → puede estar derivando demasiado a Haiku
        if ("smart_routing".equals(s.aiRoutingMode) && !s.modelDistribution.isEmpty()) {
            for (ModelDistRow m : s.modelDistribution) {
                if (m.model != null && m.model.contains("haiku") && m.pctOfCost < 15.0
                        && s.modelDistribution.size() >= 2) {
                    recs.add("Tu routing inteligente derivó <15% del costo a Haiku. " +
                        "La heurística puede estar siendo conservadora. " +
                        "Verificá si hay conversaciones simples que están yendo a Sonnet.");
                    break;
                }
            }
        }

        // Duración muy larga → posible fricción en el flujo
        if (s.avgDurationMinutes > 8.0) {
            recs.add("Las conversaciones duran " + s.avgDurationMinutes + " min en promedio. " +
                "Si es alto para tu flujo (ej: reserva), revisar si hay muchas preguntas redundantes " +
                "o si el bot pide datos en demasiados turnos.");
        }

        // Mensajes promedio muy alto → ineficiencia
        if (s.avgMessagesPerSession > 15.0) {
            recs.add("Promedio de " + s.avgMessagesPerSession + " turnos por conversación. " +
                "Considerar si el prompt puede pedir varios datos a la vez en lugar de uno por turno.");
        }

        // Si no hay nada que recomendar → mensaje positivo
        if (recs.isEmpty()) {
            recs.add("Todo se ve bien. Sin recomendaciones por ahora.");
        }

        return recs;
    }

    // Helpers tipo-safe para extraer valores de Object[] de queries nativas
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        return BigDecimal.ZERO;
    }
    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }

    // ─────────── DTOs ───────────

    public static class SummaryDto {
        public BigDecimal todayUsd;
        public BigDecimal monthUsd;
        public BigDecimal yearUsd;
        public Long todayCalls;
        public Long monthCalls;
        public Long yearCalls;
        public Long monthInputTokens;
        public Long monthOutputTokens;
        public Long monthCacheReadTokens;
        public Long monthCacheWriteTokens;
        public List<BreakdownRow> byProvider;
        public List<BreakdownRow> byFeature;
        public List<ModelRow> byModel;
    }
    public static class BreakdownRow {
        public String key;
        public BigDecimal costUsd;
        public Long calls;
    }
    public static class ModelRow {
        public String provider;
        public String model;
        public BigDecimal costUsd;
        public Long calls;
    }
    public static class TimelinePoint {
        public String date;       // "yyyy-MM-dd"
        public BigDecimal costUsd;
        public Long calls;
    }
    public static class TopSessionRow {
        public String sessionId;
        public BigDecimal costUsd;
        public Long calls;
        public Instant firstAt;
        public Instant lastAt;
    }

    // ─────────── DTOs para el nuevo botStats ───────────

    /** Punto de timeline diario del costo de chat. */
    public static class DailyPoint {
        public String date;
        public BigDecimal costUsd;
        public Long calls;
    }

    /** Fila de distribución por modelo en sesiones de chat. */
    public static class ModelDistRow {
        public String model;
        public BigDecimal costUsd;
        public Long calls;
        public Long sessions;
        public double pctOfCost;
    }

    /** Buckets de complejidad de conversaciones (por número de turnos). */
    public static class ComplexityBuckets {
        public long simple;   // 1-4 turnos
        public long medium;   // 5-10 turnos
        public long complex;  // 11+ turnos
    }

    /**
     * DTO consolidado del dashboard del bot. Una sola llamada al endpoint
     * carga todo el dashboard, sin necesidad de hacer 5 fetches separados
     * como hace el dashboard legacy.
     */
    public static class BotStatsDto {
        // Config y ventana
        public String aiRoutingMode;
        public int windowDays;
        public Instant windowStart;

        // Cards principales
        public BigDecimal avgCostPerMessage;
        public BigDecimal avgCostPerSession;
        public double avgMessagesPerSession;
        public double avgDurationMinutes;
        public double cacheHitPct;
        public long totalSessions;
        public long totalMessages;
        public BigDecimal totalCost;

        // Distribución por modelo (relevante si smart_routing)
        public List<ModelDistRow> modelDistribution;

        // Complejidad (por turnos)
        public ComplexityBuckets complexity;

        // Timeline diario
        public List<DailyPoint> daily;

        // Top sesiones más caras
        public List<TopSessionRow> topSessions;

        // Recomendaciones generadas según los números
        public List<String> recommendations;
    }
}
