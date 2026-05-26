package app.coincidir.api.errormonitor.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundle de stats que consume el dashboard del frontend. Reunido en un solo
 * objeto para que la UI haga un único request al cargar la pestaña "Resumen".
 *
 * Estructura:
 *  - kpis: tarjetas numéricas (errores hoy, 7 días, abiertos, % resueltos)
 *  - timeSeries: serie por día con breakdown por tipo (para gráfico stacked)
 *  - byType: torta de distribución por errorType
 *  - byLevel: torta error/warn/fatal
 *  - bySource: distribución front/back/system
 *  - byStatus: open/resolved/ignored
 *  - topRecurring: errores más repetidos en el rango (con fingerprint)
 *  - reconnects: contadores de reconexiones DB y Anthropic
 */
public class ErrorMonitorStatsDto {

    public Instant generatedAt;
    public Instant rangeFrom;
    public Instant rangeTo;
    public String  bucket;          // "day" | "week" | "month"

    public Kpis kpis = new Kpis();

    /** Serie temporal: una entry por día, con counts por tipo. */
    public List<TimeBucket> timeSeries;

    /** Map errorType → count en el rango. */
    public Map<String, Long> byType = new LinkedHashMap<>();
    public Map<String, Long> byLevel = new LinkedHashMap<>();
    public Map<String, Long> bySource = new LinkedHashMap<>();
    public Map<String, Long> byStatus = new LinkedHashMap<>();

    public List<TopError> topRecurring;

    public long dbReconnects24h;
    public long dbReconnects7d;
    public long dbErrors24h;
    public long anthropicErrors24h;

    public static class Kpis {
        public long errorsToday;
        public long errorsLast7Days;
        public long errorsLast30Days;
        public long open;
        public long resolved;
        public long ignored;
        /** % de errores resueltos del rango (0-100). */
        public double resolvedRate;
    }

    public static class TimeBucket {
        public LocalDate day;
        /** Mapa errorType → count en este día. */
        public Map<String, Long> byType = new LinkedHashMap<>();
        public long total;
    }

    public static class TopError {
        public String fingerprint;
        public String shortDesc;
        public String errorType;
        public String level;
        public long   count;
        public Instant lastSeen;
        public Instant firstSeen;
    }
}
