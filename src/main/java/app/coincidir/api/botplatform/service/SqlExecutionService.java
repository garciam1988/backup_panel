package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.BotQueryLog;
import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.BotQueryLogRepository;
import app.coincidir.api.botplatform.repository.ConnectorSchemaCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SqlExecutionService — el corazón de la tool execute_sql (Fase 2).
 *
 * Flujo:
 *   1. Validar el conector (existe, activo, tiene sqlExecEnabled).
 *   2. Validar el SQL con SqlSafetyValidator (SELECT-only, AST safe).
 *   3. Forzar/inyectar LIMIT máximo si no lo trae (capear filas devueltas).
 *   4. Ejecutar contra el DataSource del conector con setQueryTimeout.
 *   5. Recolectar filas hasta el cap; medir tamaño bytes; truncar si excede.
 *   6. Loggear en bot_query_log (status, duration, rows, bytes, error).
 *   7. Devolver resultado tipado al caller (REST o pipeline del bot).
 *
 * El servicio NO sabe nada de Anthropic ni de tools del LLM. Eso lo
 * orquesta más arriba (Fase 2 sub-paso 6). Acá es agnóstico — lo puede
 * usar un endpoint REST de admin para testear.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutionService {

    private final BotConnectorRepository connectorRepo;
    private final BotQueryLogRepository queryLogRepo;
    private final ConnectorSchemaCacheRepository schemaCacheRepo;
    private final DynamicDataSourceService dataSourceService;
    private final SqlSafetyValidator validator;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** Payload de respuesta — lo que devolvemos al caller (bot o endpoint REST). */
    public record ExecResult(
            boolean ok,
            String status,            // OK | VALIDATION_FAILED | TIMEOUT | SQL_ERROR | TRUNCATED | DISABLED
            String errorMessage,
            String executedSql,       // SQL final (con LIMIT inyectado si aplicó)
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowsReturned,
            boolean truncated,
            long durationMs
    ) {}

    /**
     * Ejecuta la query. Si algo falla, queda registrado en bot_query_log y
     * el ExecResult devuelve ok=false con un mensaje accionable. Nunca
     * propaga excepción al caller (todas se capturan).
     *
     * @param connectorId conector contra el que ejecutar
     * @param rawSql      SQL que armó el LLM (puede o no traer LIMIT)
     * @param sessionId   id de la conversación que disparó la query, null si manual
     * @param userQuestion pregunta del usuario que llevó a la query, null si manual
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExecResult execute(Long connectorId, String rawSql, String sessionId, String userQuestion) {
        long t0 = System.currentTimeMillis();

        // 1. Conector
        BotConnector connector = connectorRepo.findById(connectorId).orElse(null);
        if (connector == null) {
            return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                    "VALIDATION_FAILED", "Conector no encontrado: " + connectorId,
                    null, null, 0, false, System.currentTimeMillis() - t0);
        }
        if (!Boolean.TRUE.equals(connector.getActive())) {
            return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                    "DISABLED", "El conector está inactivo.",
                    null, null, 0, false, System.currentTimeMillis() - t0);
        }
        if (!connector.isSqlExecEnabled()) {
            return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                    "DISABLED", "SQL libre no está habilitado en este conector.",
                    null, null, 0, false, System.currentTimeMillis() - t0);
        }

        // 2. Validación de seguridad
        SqlSafetyValidator.ValidationResult v = validator.validate(rawSql);
        if (!v.ok()) {
            return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                    "VALIDATION_FAILED", v.reason(),
                    null, null, 0, false, System.currentTimeMillis() - t0);
        }

        // 2.5. Whitelist de tablas (si está configurada)
        //
        // Si el conector tiene `sqlExecTableWhitelist` con valores, solo
        // aceptamos queries que referencien tablas de esa lista. Defensa en
        // profundidad: el llmSummary que ve Claude ya viene filtrado en
        // PublicSqlExecController.info, así que en condiciones normales
        // Claude no podría saber de las otras tablas. Pero alguien podría
        // intentar inyectar una tabla directamente vía /api/public/sql-exec/execute,
        // o Claude podría memorizar nombres de tablas de otros clientes.
        java.util.Set<String> whitelist = connector.getTableWhitelist();
        if (!whitelist.isEmpty()) {
            java.util.Set<String> referenced = extractReferencedTables(rawSql);
            java.util.List<String> notAllowed = new java.util.ArrayList<>();
            for (String t : referenced) {
                if (!whitelist.contains(t.toLowerCase(java.util.Locale.ROOT))) {
                    notAllowed.add(t);
                }
            }
            if (!notAllowed.isEmpty()) {
                String reason = "Acceso denegado a tabla(s) fuera de la whitelist: " +
                        String.join(", ", notAllowed) + ". " +
                        "Tablas permitidas en este conector: " + String.join(", ", whitelist) + ".";
                return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                        "VALIDATION_FAILED", reason,
                        null, null, 0, false, System.currentTimeMillis() - t0);
            }
        }

        // 2.7. Rate limiting (Fase 4 entrega B)
        //
        // Si el conector tiene rate_limit_per_minute/per_day configurados,
        // contamos cuántas queries ya se ejecutaron en la ventana y
        // rechazamos si excede. La cuenta es contra bot_query_log usando
        // los queries countExecutedSince/countExecutedBySessionSince.
        //
        // Costo: 1-3 SELECTs COUNT con filtro indexado (connector_id, created_at).
        // Suficientemente rápido para el caso típico (<1ms con índice apropiado).
        //
        // No usamos un contador en memoria por dos razones:
        //   1. El backend puede correr múltiples instancias en el futuro
        //      (Railway escalado). Un contador in-memory por instancia
        //      no comparte estado y permite burst en cada una.
        //   2. La fuente de verdad ya es bot_query_log — más simple
        //      consultarlo que mantener un cache paralelo.
        String rateLimitErr = checkRateLimits(connector, sessionId);
        if (rateLimitErr != null) {
            return logAndReturn(connectorId, sessionId, userQuestion, rawSql,
                    "RATE_LIMITED", rateLimitErr,
                    null, null, 0, false, System.currentTimeMillis() - t0);
        }

        // 3. Forzar LIMIT (si no trae, lo agregamos; si trae más alto, lo bajamos)
        int maxRows = connector.getMaxRows();
        String safeSql = forceLimit(rawSql, maxRows, connector.getDbType());

        // 4 + 5. Ejecutar con timeout
        try {
            DataSource ds = dataSourceService.getDataSource(connector);
            ExecutionOutcome outcome = runQuery(ds, safeSql, connector.getTimeoutSec(), maxRows, connector.getMaxBytes());

            long duration = System.currentTimeMillis() - t0;
            String status = outcome.truncated ? "TRUNCATED" : "OK";

            // 6. Log + return
            BotQueryLog logRow = persistLog(connectorId, sessionId, userQuestion, safeSql,
                    status, null, outcome.rows.size(), duration, outcome.bytes);
            log.debug("[sql-exec] log id={} status={} rows={} dur={}ms",
                    logRow.getId(), status, outcome.rows.size(), duration);

            return new ExecResult(
                    true, status, null,
                    safeSql, outcome.columns, outcome.rows,
                    outcome.rows.size(), outcome.truncated, duration
            );

        } catch (SQLTimeoutException timeout) {
            long duration = System.currentTimeMillis() - t0;
            return logAndReturn(connectorId, sessionId, userQuestion, safeSql,
                    "TIMEOUT",
                    "La query tardó más de " + connector.getTimeoutSec() + "s. " +
                    "Probá filtrar más o pedir menos columnas. Mensaje: " + timeout.getMessage(),
                    null, null, 0, false, duration);
        } catch (SQLException sqlEx) {
            long duration = System.currentTimeMillis() - t0;
            String enriched = enrichSqlError(sqlEx, connectorId);
            return logAndReturn(connectorId, sessionId, userQuestion, safeSql,
                    "SQL_ERROR", enriched,
                    null, null, 0, false, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - t0;
            log.warn("[sql-exec] error inesperado: {}", e.getMessage(), e);
            return logAndReturn(connectorId, sessionId, userQuestion, safeSql,
                    "SQL_ERROR",
                    "Error inesperado: " + e.getClass().getSimpleName() + " - " + truncate(e.getMessage(), 300),
                    null, null, 0, false, duration);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inyección de LIMIT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Si el SQL tiene LIMIT mayor al máximo del conector, lo baja. Si no
     * tiene, agrega LIMIT N. Solo para MySQL/Postgres/MariaDB (sintaxis
     * estándar). SQL Server usa TOP, Oracle usa FETCH FIRST — para esos
     * dos, agregamos un guard distinto: si el LLM no usó TOP/FETCH y
     * supera maxRows, truncamos en código al recolectar (Sub-paso 5).
     *
     * Para no complicar la Fase 2, MySQL/Postgres usan LIMIT explícito
     * y los otros dos confían en el truncate al cobrar filas — funciona
     * idénticamente desde el punto de vista del bot, solo que la query
     * en sí no tiene el cap visible.
     */
    private String forceLimit(String sql, int maxRows, BotConnector.DbType dbType) {
        if (dbType != BotConnector.DbType.MYSQL
                && dbType != BotConnector.DbType.MARIADB
                && dbType != BotConnector.DbType.POSTGRES) {
            // SQL Server / Oracle: truncate al cobrar filas
            return sql.trim();
        }
        try {
            // JSqlParser 5.x: parse() devuelve un Statement; si era SELECT,
            // ya viene como PlainSelect/SetOperationList/Values (sin
            // getSelectBody intermedio). Casteamos directo.
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof PlainSelect ps) {
                Limit existing = ps.getLimit();
                if (existing == null) {
                    Limit newLimit = new Limit();
                    newLimit.setRowCount(new LongValue(maxRows));
                    ps.setLimit(newLimit);
                } else if (existing.getRowCount() instanceof LongValue lv) {
                    if (lv.getValue() > maxRows) {
                        existing.setRowCount(new LongValue(maxRows));
                    }
                }
                return ps.toString();
            }
            // Otros tipos de Select (UNION, etc.): devolvemos como está;
            // el guard de maxRows + maxBytes en runQuery() lo cubre igual.
            return stmt.toString();
        } catch (JSQLParserException e) {
            log.warn("[sql-exec] forceLimit no pudo re-parsear sql, dejándolo crudo: {}", e.getMessage());
        }
        return sql.trim();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Ejecución
    // ─────────────────────────────────────────────────────────────────────

    /** Resultado intermedio antes de armar el ExecResult. */
    private static class ExecutionOutcome {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int bytes = 0;
    }

    private ExecutionOutcome runQuery(DataSource ds, String sql, int timeoutSec, int maxRows, int maxBytes)
            throws SQLException {
        ExecutionOutcome out = new ExecutionOutcome();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSec);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    out.columns.add(md.getColumnLabel(i));
                }
                while (rs.next()) {
                    if (out.rows.size() >= maxRows) {
                        out.truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    int rowBytes = 0;
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        // Convertir tipos no-JSON-friendly a String
                        if (val instanceof Clob clob) {
                            val = clob.getSubString(1, (int) Math.min(clob.length(), 10_000));
                        } else if (val instanceof Blob) {
                            val = "<BLOB>";
                        }
                        row.put(out.columns.get(i - 1), val);
                        rowBytes += approxBytes(val);
                    }
                    out.rows.add(row);
                    out.bytes += rowBytes;
                    if (out.bytes > maxBytes) {
                        out.truncated = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private int approxBytes(Object v) {
        if (v == null) return 4;
        if (v instanceof String s) return s.length() * 2; // utf-8 worst case
        if (v instanceof Number) return 8;
        if (v instanceof Boolean) return 1;
        return v.toString().length() * 2;
    }

    /**
     * Aplica los rate limits configurados en el conector. Devuelve un
     * mensaje de error si alguno se excedió, o null si todo OK.
     *
     * Orden de chequeo: minuto global → día global → minuto por sesión.
     * Devolvemos en el primer fallo (no chequeamos todos) para responder
     * rápido. El mensaje incluye:
     *   - Qué límite se excedió
     *   - Cuántas queries hay en la ventana
     *   - Cuándo aproximadamente se libera (retryAfterSec)
     *
     * Si los tres campos del conector son null/cero, ni siquiera consultamos
     * la BD — devolvemos null directo. Cero overhead para usuarios que no
     * configuraron rate limit.
     */
    private String checkRateLimits(BotConnector connector, String sessionId) {
        Integer perMin = connector.getSqlExecRateLimitPerMinute();
        Integer perDay = connector.getSqlExecRateLimitPerDay();
        Integer perSesMin = connector.getSqlExecRateLimitPerSessionMinute();

        boolean hasAny = (perMin != null && perMin > 0)
                      || (perDay != null && perDay > 0)
                      || (perSesMin != null && perSesMin > 0);
        if (!hasAny) return null;

        Long connectorId = connector.getId();
        Instant now = Instant.now();

        // 1) Por minuto global
        if (perMin != null && perMin > 0) {
            Instant since = now.minus(1, java.time.temporal.ChronoUnit.MINUTES);
            long count = queryLogRepo.countExecutedSince(connectorId, since);
            if (count >= perMin) {
                return String.format(
                        "Rate limit alcanzado: %d queries en el último minuto (límite: %d). " +
                        "Esperá unos segundos antes de reintentar.",
                        count, perMin);
            }
        }

        // 2) Por día global
        if (perDay != null && perDay > 0) {
            Instant since = now.minus(1, java.time.temporal.ChronoUnit.DAYS);
            long count = queryLogRepo.countExecutedSince(connectorId, since);
            if (count >= perDay) {
                return String.format(
                        "Rate limit diario alcanzado: %d queries en las últimas 24h (límite: %d). " +
                        "Se libera gradualmente con el correr del día.",
                        count, perDay);
            }
        }

        // 3) Por sesión por minuto — solo si tenemos sessionId.
        //    sessionId vacío/null = test manual del admin → no aplicamos
        //    el límite por sesión (sería raro auto-limitar el panel admin).
        if (perSesMin != null && perSesMin > 0 && sessionId != null && !sessionId.isBlank()) {
            Instant since = now.minus(1, java.time.temporal.ChronoUnit.MINUTES);
            long count = queryLogRepo.countExecutedBySessionSince(connectorId, sessionId, since);
            if (count >= perSesMin) {
                return String.format(
                        "Esta sesión hizo %d queries en el último minuto (límite por sesión: %d). " +
                        "Esperá unos segundos antes de seguir preguntando cosas de la BD.",
                        count, perSesMin);
            }
        }

        return null;
    }

    /**
     * Extrae los nombres de tablas referenciadas en un SQL. Usa el
     * TablesNamesFinder de JSqlParser que recorre el AST (cubre joins,
     * subqueries, CTEs, etc.).
     *
     * Si el parseo falla (raro porque el SqlSafetyValidator ya validó que
     * es un SELECT parseable un par de pasos antes), devolvemos un set
     * con un marcador "__parse_failed__" que nunca matchea contra la
     * whitelist → la query queda bloqueada. Esto es "fail closed":
     * en duda, bloqueamos antes que permitir.
     *
     * Los nombres se devuelven en minúsculas y sin schema/quoting para
     * comparar limpio contra la whitelist (que también está normalizada).
     */
    private java.util.Set<String> extractReferencedTables(String sql) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            net.sf.jsqlparser.util.TablesNamesFinder finder = new net.sf.jsqlparser.util.TablesNamesFinder();
            java.util.List<String> names = finder.getTableList(stmt);
            // Las tablas pueden venir como "schema.tabla" en Postgres o
            // "[dbo].[tabla]" en SQL Server o "`tabla`" en MySQL.
            // Normalizamos: sin brackets/quotes, sin schema, lowercase.
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String n : names) {
                String clean = n
                        .replace("[", "").replace("]", "")
                        .replace("\"", "").replace("`", "");
                int dotIdx = clean.lastIndexOf('.');
                if (dotIdx >= 0 && dotIdx < clean.length() - 1) {
                    clean = clean.substring(dotIdx + 1);
                }
                out.add(clean.toLowerCase(java.util.Locale.ROOT));
            }
            return out;
        } catch (Exception e) {
            log.warn("[sql-exec] extractReferencedTables falló: {}. SQL: {}",
                    e.getMessage(), truncate(sql, 200));
            // Fail closed: marcador que nunca matchea con la whitelist
            // real → la query se rechaza.
            return java.util.Set.of("__parse_failed__");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Enrichment de errores SQL para que el LLM pueda corregir y reintentar
    // ─────────────────────────────────────────────────────────────────────
    //
    // Cuando Claude arma un SQL contra el schema cacheado y se equivoca de
    // columna/tabla, el motor devuelve algo críptico tipo:
    //   "Unknown column 'cliente_nombre' in 'field list'" (MySQL)
    //   "column \"cliente_nombre\" does not exist" (Postgres)
    //   "Invalid column name 'cliente_nombre'" (SQL Server)
    //
    // Si le devolvemos ese mensaje pelado al LLM, normalmente reintenta a
    // ciegas con otra variación que también puede estar mal. Pero si le
    // damos las columnas/tablas REALES del schema cacheado, el LLM puede
    // corregir bien al primer intento.
    //
    // Esta función:
    //   1. Detecta los 3 patrones más comunes (columna inexistente, tabla
    //      inexistente, sintaxis cerca de palabra).
    //   2. Lee el schema cacheado del conector.
    //   3. Si encuentra match, agrega contexto accionable al mensaje.
    //   4. Si no, devuelve el mensaje original (sin enrichment).
    //
    // No usa LLM ni nada caro — pura regex + lookup en memoria.

    private static final Pattern PAT_UNKNOWN_COLUMN_MYSQL =
            Pattern.compile("Unknown column '([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_UNKNOWN_COLUMN_PG =
            Pattern.compile("column \"([^\"]+)\" does not exist", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_INVALID_COLUMN_MSSQL =
            Pattern.compile("Invalid column name '([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_UNKNOWN_TABLE_MYSQL =
            Pattern.compile("Table '[^']*\\.([^']+)' doesn't exist", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_UNKNOWN_TABLE_PG =
            Pattern.compile("relation \"([^\"]+)\" does not exist", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_INVALID_TABLE_MSSQL =
            Pattern.compile("Invalid object name '([^']+)'", Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unchecked")
    private String enrichSqlError(SQLException sqlEx, Long connectorId) {
        String baseMsg = sqlEx.getMessage() == null ? "Error SQL desconocido" : sqlEx.getMessage();

        // Cargamos el schema cacheado. Si no hay, no podemos enriquecer.
        ConnectorSchemaCache cache = schemaCacheRepo.findByConnectorId(connectorId).orElse(null);
        if (cache == null || cache.getSchemaJson() == null) {
            return "Error ejecutando SQL: " + truncate(baseMsg, 500);
        }

        Map<String, Object> schema;
        try {
            schema = jsonMapper.readValue(cache.getSchemaJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception parseEx) {
            return "Error ejecutando SQL: " + truncate(baseMsg, 500);
        }
        List<Map<String, Object>> tables = (List<Map<String, Object>>) schema.getOrDefault("tables", List.of());

        // 1) Columna inexistente
        String badColumn = matchAny(baseMsg,
                PAT_UNKNOWN_COLUMN_MYSQL, PAT_UNKNOWN_COLUMN_PG, PAT_INVALID_COLUMN_MSSQL);
        if (badColumn != null) {
            // Buscamos en qué tabla(s) podría haber querido decir esa columna,
            // y proponemos columnas similares.
            String hint = buildColumnHint(badColumn, tables);
            return "La columna '" + badColumn + "' no existe en el schema. " + hint +
                   " Ajustá el SQL usando solo columnas del schema y reintentá. " +
                   "Mensaje original: " + truncate(baseMsg, 300);
        }

        // 2) Tabla inexistente
        String badTable = matchAny(baseMsg,
                PAT_UNKNOWN_TABLE_MYSQL, PAT_UNKNOWN_TABLE_PG, PAT_INVALID_TABLE_MSSQL);
        if (badTable != null) {
            String hint = buildTableHint(badTable, tables);
            return "La tabla '" + badTable + "' no existe en la base. " + hint +
                   " Ajustá el SQL usando solo tablas del schema y reintentá. " +
                   "Mensaje original: " + truncate(baseMsg, 300);
        }

        // 3) No matcheamos ningún patrón conocido → mensaje crudo
        return "Error ejecutando SQL: " + truncate(baseMsg, 500);
    }

    /** Devuelve el primer grupo capturado del primer pattern que matchee, o null. */
    private String matchAny(String text, Pattern... patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Para una columna que no existe, busca tablas que sí tienen columnas
     * similares (por substring) y devuelve sugerencias en lenguaje natural.
     * Limita a las 3 tablas más prometedoras para no inflar el mensaje.
     */
    @SuppressWarnings("unchecked")
    private String buildColumnHint(String badColumn, List<Map<String, Object>> tables) {
        String needle = badColumn.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("name");
            List<Map<String, Object>> columns = (List<Map<String, Object>>) table.getOrDefault("columns", List.of());
            List<String> colNames = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                colNames.add(String.valueOf(col.get("name")));
            }
            // Match exacto en otra tabla
            for (String c : colNames) {
                if (c.equalsIgnoreCase(badColumn)) {
                    matches.add("La columna '" + c + "' existe en la tabla '" + tableName + "'.");
                    break;
                }
            }
            // Sugerencia por substring (ej: el LLM puso "cliente_nombre" y la real es "nombre")
            List<String> similar = new ArrayList<>();
            for (String c : colNames) {
                String cl = c.toLowerCase(Locale.ROOT);
                if (cl.contains(needle) || needle.contains(cl)) {
                    similar.add(c);
                }
            }
            if (!similar.isEmpty()) {
                matches.add("En '" + tableName + "' columnas parecidas: " + String.join(", ", similar) + ".");
            }
            if (matches.size() >= 3) break;
        }
        if (matches.isEmpty()) return "No encontré columnas parecidas en el schema.";
        return String.join(" ", matches);
    }

    /**
     * Para una tabla que no existe, busca tablas con nombre similar
     * y propone alternativas.
     */
    private String buildTableHint(String badTable, List<Map<String, Object>> tables) {
        String needle = badTable.toLowerCase(Locale.ROOT);
        List<String> similar = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            String name = String.valueOf(t.get("name"));
            String nl = name.toLowerCase(Locale.ROOT);
            if (nl.contains(needle) || needle.contains(nl)) {
                similar.add(name);
            }
            if (similar.size() >= 5) break;
        }
        if (similar.isEmpty()) {
            // Si no hay nada parecido, listamos las primeras 8 tablas como referencia
            List<String> sample = new ArrayList<>();
            for (Map<String, Object> t : tables) {
                sample.add(String.valueOf(t.get("name")));
                if (sample.size() >= 8) break;
            }
            return "Tablas disponibles (muestra): " + String.join(", ", sample) + ".";
        }
        return "Tablas parecidas: " + String.join(", ", similar) + ".";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Persistencia del log
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public BotQueryLog persistLog(Long connectorId, String sessionId, String userQuestion,
                                  String sql, String status, String errorMsg,
                                  int rowsReturned, long durationMs, int resultBytes) {
        BotQueryLog row = new BotQueryLog();
        row.setConnectorId(connectorId);
        row.setSessionId(truncate(sessionId, 64));
        row.setUserQuestion(truncate(userQuestion, 500));
        row.setSqlText(sql == null ? "" : sql);
        row.setStatus(status);
        row.setErrorMessage(truncate(errorMsg, 1000));
        row.setRowsReturned(rowsReturned);
        row.setDurationMs(durationMs);
        row.setResultSizeBytes(resultBytes);
        return queryLogRepo.save(row);
    }

    /** Helper: persiste el log y devuelve un ExecResult de fallo en un solo paso. */
    private ExecResult logAndReturn(Long connectorId, String sessionId, String userQuestion,
                                    String sql, String status, String errorMsg,
                                    List<String> cols, List<Map<String, Object>> rows,
                                    int rowsReturned, boolean truncated, long durationMs) {
        try {
            persistLog(connectorId, sessionId, userQuestion, sql, status, errorMsg, rowsReturned, durationMs, 0);
        } catch (Exception logEx) {
            // No queremos que un fallo de log rompa la respuesta
            log.warn("[sql-exec] no se pudo persistir bot_query_log: {}", logEx.getMessage());
        }
        return new ExecResult(false, status, errorMsg, sql, cols, rows, rowsReturned, truncated, durationMs);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
