package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.domain.ConnectorTableDescription;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.ConnectorSchemaCacheRepository;
import app.coincidir.api.botplatform.repository.ConnectorTableDescriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/**
 * SchemaIntrospectionService — recorre INFORMATION_SCHEMA del BotConnector
 * para obtener el mapa de tablas, columnas, PKs y FKs.
 *
 * El resultado se guarda en {@code connector_schema_cache} (1 fila por
 * conector) y se devuelve. La idea es introspectar una vez al onboarding
 * (o cuando el cliente lo pida con un botón "Re-escanear") y reusar el
 * snapshot para todas las queries del bot, en vez de pegar a
 * INFORMATION_SCHEMA cada turno.
 *
 * Soporta MySQL/MariaDB, Postgres, SQL Server y Oracle. Cada motor tiene
 * sus quirks pero todos exponen INFORMATION_SCHEMA (o vista equivalente).
 *
 * Limitación práctica: traemos como máximo MAX_TABLES tablas y MAX_COLS_PER_TABLE
 * columnas por tabla para no explotar en BDs gigantes. El cliente con BD de
 * 5000 tablas probablemente necesita un patrón más sofisticado (filtrar por
 * schema/prefijo), pero para el 95% de los casos esto alcanza.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaIntrospectionService {

    private static final int MAX_TABLES = 500;
    private static final int MAX_COLS_PER_TABLE = 200;

    private final BotConnectorRepository connectorRepo;
    private final ConnectorSchemaCacheRepository cacheRepo;
    private final ConnectorTableDescriptionRepository descRepo;
    private final DynamicDataSourceService dataSourceService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Introspecta el conector y guarda el snapshot. Si ya había uno, lo
     * reemplaza. Devuelve el cache actualizado.
     */
    @Transactional
    public ConnectorSchemaCache introspect(Long connectorId) throws Exception {
        BotConnector connector = connectorRepo.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Conector no encontrado: " + connectorId));

        log.info("[schema-introspect] conector={} ({}), iniciando…", connector.getName(), connector.getDbType());

        DataSource ds = dataSourceService.getDataSource(connector);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Cada motor expone metadata distinto. Delegamos al dialect.
        Map<String, Object> schema = switch (connector.getDbType()) {
            case MYSQL, MARIADB -> introspectMysql(jdbc, connector.getDatabaseName());
            case POSTGRES       -> introspectPostgres(jdbc, connector.getDatabaseName());
            case SQLSERVER      -> introspectSqlServer(jdbc, connector.getDatabaseName());
            case ORACLE         -> introspectOracle(jdbc);
        };
        schema.put("dbType", connector.getDbType().name());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) schema.getOrDefault("tables", List.of());

        int columnCount = tables.stream()
                .mapToInt(t -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cols = (List<Map<String, Object>>) t.getOrDefault("columns", List.of());
                    return cols.size();
                })
                .sum();

        String schemaJson = jsonMapper.writeValueAsString(schema);
        String llmSummary = buildLlmSummary(connector, tables);

        ConnectorSchemaCache cache = cacheRepo.findByConnectorId(connectorId)
                .orElseGet(ConnectorSchemaCache::new);
        cache.setConnectorId(connectorId);
        cache.setSchemaJson(schemaJson);
        cache.setLlmSummary(llmSummary);
        cache.setTableCount(tables.size());
        cache.setColumnCount(columnCount);
        cache.setRefreshedAt(Instant.now());

        cache = cacheRepo.save(cache);

        log.info("[schema-introspect] conector={} listo: {} tablas, {} columnas",
                connector.getName(), tables.size(), columnCount);

        return cache;
    }

    public Optional<ConnectorSchemaCache> getCached(Long connectorId) {
        return cacheRepo.findByConnectorId(connectorId);
    }

    /**
     * Re-genera el llmSummary del cache reusando el schemaJson ya escaneado.
     * No vuelve a pegar a la BD del cliente — solo re-arma el texto que se le
     * sirve al LLM, leyendo del schemaJson cacheado + glossary del conector +
     * descripciones por tabla cargadas en `connector_table_description`.
     *
     * Se usa cuando el admin cambia el glossary o las descripciones: queremos
     * que el cambio se refleje sin forzar un re-scan completo (que sería
     * lento y además podría fallar si la BD del cliente está down justo en
     * ese momento — innecesario para un cambio de texto).
     *
     * Si no hay cache previa (nunca se introspectó), no hace nada y devuelve
     * empty. El llmSummary se va a generar la próxima vez que se haga el
     * scan inicial.
     */
    @Transactional
    public Optional<ConnectorSchemaCache> regenerateLlmSummary(Long connectorId) {
        ConnectorSchemaCache cache = cacheRepo.findByConnectorId(connectorId).orElse(null);
        if (cache == null || cache.getSchemaJson() == null) {
            return Optional.empty();
        }
        BotConnector connector = connectorRepo.findById(connectorId).orElse(null);
        if (connector == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = jsonMapper.readValue(cache.getSchemaJson(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) schema.getOrDefault("tables", List.of());
            String newSummary = buildLlmSummary(connector, tables);
            cache.setLlmSummary(newSummary);
            return Optional.of(cacheRepo.save(cache));
        } catch (Exception e) {
            log.warn("[schema-introspect] regenerateLlmSummary falló para connector {}: {}",
                    connectorId, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dialectos por motor
    // ─────────────────────────────────────────────────────────────────────

    /**
     * MySQL / MariaDB: INFORMATION_SCHEMA.TABLES + COLUMNS + KEY_COLUMN_USAGE.
     * Usamos TABLE_SCHEMA = databaseName del conector para no traer "mysql",
     * "information_schema", "performance_schema", "sys".
     */
    private Map<String, Object> introspectMysql(JdbcTemplate jdbc, String dbName) {
        List<String> tableNames = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
                "ORDER BY TABLE_NAME LIMIT ?",
                String.class, dbName, MAX_TABLES);

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);

            // Columnas
            List<Map<String, Object>> columns = jdbc.query(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                    "ORDER BY ORDINAL_POSITION LIMIT ?",
                    (rs, i) -> {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", rs.getString("COLUMN_NAME"));
                        col.put("type", rs.getString("COLUMN_TYPE"));
                        col.put("nullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                        if ("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY"))) col.put("pk", true);
                        return col;
                    },
                    dbName, tableName, MAX_COLS_PER_TABLE);
            table.put("columns", columns);

            // FKs
            List<Map<String, Object>> fks = jdbc.query(
                    "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
                    "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL",
                    (rs, i) -> {
                        Map<String, Object> fk = new LinkedHashMap<>();
                        fk.put("column", rs.getString("COLUMN_NAME"));
                        fk.put("refTable", rs.getString("REFERENCED_TABLE_NAME"));
                        fk.put("refColumn", rs.getString("REFERENCED_COLUMN_NAME"));
                        return fk;
                    },
                    dbName, tableName);
            if (!fks.isEmpty()) table.put("foreignKeys", fks);

            tables.add(table);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", tables);
        return result;
    }

    /**
     * Postgres: INFORMATION_SCHEMA + pg_catalog para PKs. Filtramos a schema
     * 'public' por default; en producción puede que el cliente use otros
     * schemas — para Fase 1 alcanza con public.
     */
    private Map<String, Object> introspectPostgres(JdbcTemplate jdbc, String dbName) {
        List<String> tableNames = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name LIMIT ?",
                String.class, MAX_TABLES);

        // PKs en bulk (más eficiente que por tabla)
        Map<String, Set<String>> pksByTable = new HashMap<>();
        jdbc.query(
                "SELECT tc.table_name, kcu.column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "  ON tc.constraint_name = kcu.constraint_name " +
                " AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = 'public'",
                rs -> {
                    pksByTable.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                              .add(rs.getString(2));
                });

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);
            table.put("schema", "public");

            Set<String> pks = pksByTable.getOrDefault(tableName, Set.of());

            List<Map<String, Object>> columns = jdbc.query(
                    "SELECT column_name, data_type, is_nullable, character_maximum_length " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = ? " +
                    "ORDER BY ordinal_position LIMIT ?",
                    (rs, i) -> {
                        Map<String, Object> col = new LinkedHashMap<>();
                        String name = rs.getString("column_name");
                        col.put("name", name);
                        String type = rs.getString("data_type");
                        Integer maxLen = (Integer) rs.getObject("character_maximum_length");
                        if (maxLen != null && maxLen > 0) type += "(" + maxLen + ")";
                        col.put("type", type.toUpperCase());
                        col.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                        if (pks.contains(name)) col.put("pk", true);
                        return col;
                    },
                    tableName, MAX_COLS_PER_TABLE);
            table.put("columns", columns);

            // FKs
            List<Map<String, Object>> fks = jdbc.query(
                    "SELECT kcu.column_name, ccu.table_name AS ref_table, ccu.column_name AS ref_column " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name " +
                    "JOIN information_schema.constraint_column_usage ccu " +
                    "  ON ccu.constraint_name = tc.constraint_name " +
                    "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                    "  AND tc.table_schema = 'public' " +
                    "  AND tc.table_name = ?",
                    (rs, i) -> {
                        Map<String, Object> fk = new LinkedHashMap<>();
                        fk.put("column", rs.getString("column_name"));
                        fk.put("refTable", rs.getString("ref_table"));
                        fk.put("refColumn", rs.getString("ref_column"));
                        return fk;
                    },
                    tableName);
            if (!fks.isEmpty()) table.put("foreignKeys", fks);

            tables.add(table);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", tables);
        return result;
    }

    /**
     * SQL Server: INFORMATION_SCHEMA disponible. Usamos TOP en vez de LIMIT
     * y filtramos a tablas con TABLE_TYPE='BASE TABLE'. PKs vía
     * sys.key_constraints para evitar quirks de INFORMATION_SCHEMA.
     */
    private Map<String, Object> introspectSqlServer(JdbcTemplate jdbc, String dbName) {
        List<String> tableNames = jdbc.queryForList(
                "SELECT TOP " + MAX_TABLES + " TABLE_NAME " +
                "FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_CATALOG = ? " +
                "ORDER BY TABLE_NAME",
                String.class, dbName);

        // PKs en bulk
        Map<String, Set<String>> pksByTable = new HashMap<>();
        jdbc.query(
                "SELECT kc.parent_object_id AS tid, OBJECT_NAME(kc.parent_object_id) AS table_name, c.name AS column_name " +
                "FROM sys.key_constraints kc " +
                "JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id " +
                "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id " +
                "WHERE kc.type = 'PK'",
                rs -> {
                    pksByTable.computeIfAbsent(rs.getString("table_name"), k -> new HashSet<>())
                              .add(rs.getString("column_name"));
                });

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);

            Set<String> pks = pksByTable.getOrDefault(tableName, Set.of());

            List<Map<String, Object>> columns = jdbc.query(
                    "SELECT TOP " + MAX_COLS_PER_TABLE + " COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = ? AND TABLE_CATALOG = ? " +
                    "ORDER BY ORDINAL_POSITION",
                    (rs, i) -> {
                        Map<String, Object> col = new LinkedHashMap<>();
                        String name = rs.getString("COLUMN_NAME");
                        col.put("name", name);
                        String type = rs.getString("DATA_TYPE");
                        Integer maxLen = (Integer) rs.getObject("CHARACTER_MAXIMUM_LENGTH");
                        if (maxLen != null && maxLen > 0) type += "(" + maxLen + ")";
                        col.put("type", type.toUpperCase());
                        col.put("nullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                        if (pks.contains(name)) col.put("pk", true);
                        return col;
                    },
                    tableName, dbName);
            table.put("columns", columns);

            // FKs vía INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS + KEY_COLUMN_USAGE
            List<Map<String, Object>> fks = jdbc.query(
                    "SELECT kcu.COLUMN_NAME, ccu.TABLE_NAME AS REF_TABLE, ccu.COLUMN_NAME AS REF_COLUMN " +
                    "FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc " +
                    "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME " +
                    "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ccu ON ccu.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME " +
                    "WHERE kcu.TABLE_NAME = ?",
                    (rs, i) -> {
                        Map<String, Object> fk = new LinkedHashMap<>();
                        fk.put("column", rs.getString("COLUMN_NAME"));
                        fk.put("refTable", rs.getString("REF_TABLE"));
                        fk.put("refColumn", rs.getString("REF_COLUMN"));
                        return fk;
                    },
                    tableName);
            if (!fks.isEmpty()) table.put("foreignKeys", fks);

            tables.add(table);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", tables);
        return result;
    }

    /**
     * Oracle: usa USER_TABLES / USER_TAB_COLUMNS / USER_CONS_COLUMNS. No tiene
     * INFORMATION_SCHEMA estándar. Usamos ROWNUM para limitar.
     */
    private Map<String, Object> introspectOracle(JdbcTemplate jdbc) {
        List<String> tableNames = jdbc.queryForList(
                "SELECT table_name FROM " +
                "(SELECT table_name FROM user_tables ORDER BY table_name) " +
                "WHERE ROWNUM <= ?",
                String.class, MAX_TABLES);

        // PKs en bulk
        Map<String, Set<String>> pksByTable = new HashMap<>();
        jdbc.query(
                "SELECT cc.table_name, cc.column_name " +
                "FROM user_constraints uc " +
                "JOIN user_cons_columns cc ON uc.constraint_name = cc.constraint_name " +
                "WHERE uc.constraint_type = 'P'",
                rs -> {
                    pksByTable.computeIfAbsent(rs.getString("table_name"), k -> new HashSet<>())
                              .add(rs.getString("column_name"));
                });

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);

            Set<String> pks = pksByTable.getOrDefault(tableName, Set.of());

            List<Map<String, Object>> columns = jdbc.query(
                    "SELECT column_name, data_type, nullable, data_length " +
                    "FROM user_tab_columns " +
                    "WHERE table_name = ? AND ROWNUM <= ? " +
                    "ORDER BY column_id",
                    (rs, i) -> {
                        Map<String, Object> col = new LinkedHashMap<>();
                        String name = rs.getString("column_name");
                        col.put("name", name);
                        String type = rs.getString("data_type");
                        int dataLength = rs.getInt("data_length");
                        if (("VARCHAR2".equals(type) || "CHAR".equals(type)) && dataLength > 0) {
                            type += "(" + dataLength + ")";
                        }
                        col.put("type", type);
                        col.put("nullable", "Y".equalsIgnoreCase(rs.getString("nullable")));
                        if (pks.contains(name)) col.put("pk", true);
                        return col;
                    },
                    tableName, MAX_COLS_PER_TABLE);
            table.put("columns", columns);

            // FKs
            List<Map<String, Object>> fks = jdbc.query(
                    "SELECT acc.column_name, rcc.table_name AS ref_table, rcc.column_name AS ref_column " +
                    "FROM user_constraints ac " +
                    "JOIN user_cons_columns acc ON ac.constraint_name = acc.constraint_name " +
                    "JOIN user_cons_columns rcc ON ac.r_constraint_name = rcc.constraint_name " +
                    "WHERE ac.constraint_type = 'R' AND ac.table_name = ?",
                    (rs, i) -> {
                        Map<String, Object> fk = new LinkedHashMap<>();
                        fk.put("column", rs.getString("column_name"));
                        fk.put("refTable", rs.getString("ref_table"));
                        fk.put("refColumn", rs.getString("ref_column"));
                        return fk;
                    },
                    tableName);
            if (!fks.isEmpty()) table.put("foreignKeys", fks);

            tables.add(table);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", tables);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // LLM summary
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Genera el resumen plano que se inyecta al system prompt de Claude en
     * Fase 2. Formato tipo CREATE TABLE simplificado — Claude está
     * entrenado en este formato y entiende relaciones a partir de las FKs.
     *
     * Ejemplo de salida:
     *   -- Base: MYSQL (8 tablas, 47 columnas)
     *
     *   TABLE reservas (
     *     id INT PK,
     *     fecha DATE,
     *     cliente_id INT  -> clientes.id,
     *     ...
     *   )
     */
    private String buildLlmSummary(BotConnector connector, List<Map<String, Object>> tables) {
        StringBuilder sb = new StringBuilder();

        int totalCols = tables.stream()
                .mapToInt(t -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cols = (List<Map<String, Object>>) t.getOrDefault("columns", List.of());
                    return cols.size();
                })
                .sum();

        sb.append("-- Base: ").append(connector.getDbType()).append(" (")
          .append(tables.size()).append(" tablas, ").append(totalCols).append(" columnas)\n\n");

        // Glosario de negocio (Fase 5) — se inyecta al principio para que
        // Claude lo lea primero y tenga el contexto antes de mirar las
        // tablas. Solo se incluye si el admin lo configuró.
        String glossary = connector.getBusinessGlossary();
        if (glossary != null && !glossary.isBlank()) {
            sb.append("-- GLOSARIO DE NEGOCIO ---\n");
            // Comentamos cada línea con `-- ` para mantener el formato
            // tipo SQL comment y que Claude lo lea como contexto pasivo,
            // no como código. Truncamos al primer salto de línea final
            // para no dejar líneas vacías al final del bloque.
            for (String line : glossary.trim().split("\\R", -1)) {
                sb.append("-- ").append(line).append("\n");
            }
            sb.append("-- (fin glosario)\n\n");
        }

        // Descripciones por tabla (Fase 5). Las cargamos una sola vez en
        // un map para evitar N queries: una por tabla del schema.
        Map<String, String> descByTable = new HashMap<>();
        try {
            for (ConnectorTableDescription d : descRepo.findByConnectorIdOrderByTableNameAsc(connector.getId())) {
                if (d.getTableName() != null && d.getDescription() != null) {
                    descByTable.put(d.getTableName().toLowerCase(Locale.ROOT),
                                    d.getDescription());
                }
            }
        } catch (Exception e) {
            // Si falla la carga (improbable), seguimos sin descripciones.
            // No queremos que el introspect entero rompa por esto.
            log.warn("[schema-introspect] no pude cargar descripciones de tabla para connector {}: {}",
                    connector.getId(), e.getMessage());
        }

        for (Map<String, Object> table : tables) {
            String name = (String) table.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) table.getOrDefault("columns", List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fks = (List<Map<String, Object>>) table.getOrDefault("foreignKeys", List.of());

            // Index de FKs por columna para anotarlas inline
            Map<String, String> fkByCol = new HashMap<>();
            for (Map<String, Object> fk : fks) {
                fkByCol.put(
                        (String) fk.get("column"),
                        fk.get("refTable") + "." + fk.get("refColumn"));
            }

            // Inyectar descripción si la tabla tiene una cargada (case-insensitive).
            String desc = name == null ? null : descByTable.get(name.toLowerCase(Locale.ROOT));
            if (desc != null && !desc.isBlank()) {
                // Cada línea de la descripción va como comment, así si el
                // admin escribió múltiples líneas todas quedan claras.
                for (String line : desc.trim().split("\\R", -1)) {
                    sb.append("-- ").append(line).append("\n");
                }
            }

            sb.append("TABLE ").append(name).append(" (\n");
            for (int i = 0; i < cols.size(); i++) {
                Map<String, Object> col = cols.get(i);
                String colName = (String) col.get("name");
                String type = String.valueOf(col.get("type"));
                boolean pk = Boolean.TRUE.equals(col.get("pk"));
                boolean nullable = Boolean.TRUE.equals(col.get("nullable"));

                sb.append("  ").append(colName).append(" ").append(type);
                if (pk) sb.append(" PK");
                if (!nullable && !pk) sb.append(" NOT NULL");
                if (fkByCol.containsKey(colName)) {
                    sb.append(" -> ").append(fkByCol.get(colName));
                }
                if (i < cols.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(")\n\n");
        }

        return sb.toString();
    }
}
