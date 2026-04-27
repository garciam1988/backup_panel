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
}
