package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotConnector — Configuración de conexión a una base de datos externa (JDBC).
 *
 * Cada conector representa una BD contra la que el bot puede ejecutar tools.
 * Típicamente un cliente de la plataforma tiene 1-3 conectores (producción,
 * staging, etc.) y varias tools que apuntan a ellos.
 *
 * IMPORTANTE: las credenciales se guardan en plano por ahora (decisión explícita
 * para desarrollo). Para producción hay que agregar cifrado AES-256 con master
 * key en env var.
 */
@Entity
@Table(name = "bot_connector", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bot_connector_name", columnNames = "name")
})
@Getter @Setter
public class BotConnector {

    public enum DbType {
        MYSQL,
        POSTGRES,
        SQLSERVER,
        ORACLE,
        MARIADB
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre visible del conector (ej: "Coincidir Producción", "Restaurant La Costa"). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false, length = 20)
    private DbType dbType;

    /** Host del servidor de BD (ej: "mysql.railway.internal", "localhost"). */
    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    /** Nombre de la base de datos / schema. */
    @Column(name = "database_name", nullable = false, length = 100)
    private String databaseName;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    /**
     * Password en texto plano — por ahora (decisión explícita).
     * TODO: cifrar con AES-256 antes de producción.
     */
    @Column(name = "password", length = 500)
    private String password;

    /**
     * Parámetros extra de conexión (query string del JDBC URL).
     * Ej: "useSSL=false&serverTimezone=UTC"
     */
    @Column(name = "extra_params", length = 500)
    private String extraParams;

    /** Si está en false, el conector no se puede usar en tools. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    // ─────────────────────────────────────────────────────────────────────
    // Límites para la tool built-in execute_sql (Fase 2)
    //
    // Cuando el bot tiene SQL libre habilitado contra este conector, estos
    // valores capean el costo y el riesgo de cada query. Defaults conservadores;
    // el admin los puede subir desde el form si confía más en el setup.
    // Null en BD → se usan los defaults.
    // ─────────────────────────────────────────────────────────────────────

    /** Si está en false, la tool execute_sql NO se expone al bot para este conector. */
    @Column(name = "sql_exec_enabled", nullable = false)
    private Boolean sqlExecEnabled = false;

    /** Máximo de filas que devolverá una query del bot (default 100). */
    @Column(name = "sql_exec_max_rows")
    private Integer sqlExecMaxRows;

    /** Timeout en segundos para una query del bot (default 10). */
    @Column(name = "sql_exec_timeout_sec")
    private Integer sqlExecTimeoutSec;

    /** Tamaño máximo (en bytes) del JSON de respuesta (default 1_000_000 = 1MB). */
    @Column(name = "sql_exec_max_bytes")
    private Integer sqlExecMaxBytes;

    /**
     * Whitelist opcional de tablas que el bot puede consultar.
     *
     * Formato: CSV de nombres de tabla en minúsculas, sin espacios extra
     * (ej: "reservas,clientes,destinos"). Las comparaciones se hacen
     * case-insensitive en el servicio.
     *
     * Semántica:
     *   - null o blank → TODAS las tablas del schema están permitidas (default).
     *   - con valores  → SOLO esas tablas son visibles para el LLM y solo
     *     contra esas se pueden ejecutar queries.
     *
     * El filtro se aplica en dos lugares:
     *   1. PublicSqlExecController.info → el llmSummary que ve Claude
     *      solo incluye estas tablas. Claude no se entera de las otras.
     *   2. SqlExecutionService.execute → antes de correr la query,
     *      parseamos el SQL y verificamos que solo referencie tablas
     *      permitidas. Defensa en profundidad por si Claude se inventa
     *      una tabla o memoriza una de un cliente anterior.
     */
    @Column(name = "sql_exec_table_whitelist", columnDefinition = "TEXT")
    private String sqlExecTableWhitelist;

    /**
     * Rate limits opcionales para evitar abuso o costo descontrolado
     * (Fase 4 entrega B). Ambos campos son opt-in: null = sin límite.
     *
     * Cómo se aplican:
     *   - Antes de ejecutar una query, contamos cuántas ya se ejecutaron
     *     para este conector en la última ventana (minuto o día).
     *   - Si excede, devolvemos status=RATE_LIMITED sin tocar la BD del
     *     cliente. El SQL queda logueado con ese status para que aparezca
     *     en métricas/logs.
     *
     * Cuentas qué: queries que pegaron a la BD (OK, TRUNCATED, TIMEOUT,
     * SQL_ERROR). Excluye VALIDATION_FAILED, DISABLED, RATE_LIMITED porque
     * esas se rebotan antes de usar pool de conexiones — costo cero.
     *
     * Granularidad: por conector. NO se aplica por sessionId — eso lo hace
     * el contador adicional sqlExecRateLimitPerSessionMinute.
     */
    @Column(name = "sql_exec_rate_limit_per_minute")
    private Integer sqlExecRateLimitPerMinute;

    @Column(name = "sql_exec_rate_limit_per_day")
    private Integer sqlExecRateLimitPerDay;

    /**
     * Rate limit por sessionId (queries/minuto) — protege contra un cliente
     * concreto que se desboca, sin frenar al resto. Si la sesión excede, se
     * bloquean solo sus queries; los demás siguen sin afectar.
     *
     * Útil cuando un bot va a clientes finales y querés evitar que uno
     * solo consuma todo el cupo del límite global.
     */
    @Column(name = "sql_exec_rate_limit_per_session_minute")
    private Integer sqlExecRateLimitPerSessionMinute;

    /**
     * Glosario de negocio en lenguaje natural. Texto libre que se inyecta
     * al inicio del llmSummary (antes de cualquier tabla) para darle a
     * Claude reglas de negocio, sinónimos y definiciones que NO puede
     * inferir solo a partir del schema.
     *
     * Ejemplos típicos:
     *   - "Facturación = suma de pagos.monto con status='confirmado'"
     *   - "La semana laboral va de lunes a viernes"
     *   - "Precios en USD se convierten con parametros.cotizacion_usd"
     *   - "Cliente 'activo' = última compra hace menos de 90 días"
     *
     * null/blank = sin glosario (no se inyecta nada).
     *
     * Cuidado con el largo: el contenido va dentro del system prompt y
     * cuenta para el contexto. Recomendable mantenerlo bajo ~2000 tokens.
     * No imponemos límite duro acá — el truncado real lo hace el frontend
     * al armar la tool description si se pasa de cierto tamaño.
     */
    @Column(name = "business_glossary", columnDefinition = "TEXT")
    private String businessGlossary;

    public int getMaxRows()       { return sqlExecMaxRows    != null ? sqlExecMaxRows    : 100; }
    public int getTimeoutSec()    { return sqlExecTimeoutSec != null ? sqlExecTimeoutSec : 10; }
    public int getMaxBytes()      { return sqlExecMaxBytes   != null ? sqlExecMaxBytes   : 1_000_000; }
    public boolean isSqlExecEnabled() { return Boolean.TRUE.equals(sqlExecEnabled); }

    /**
     * Devuelve la whitelist como Set<String> en minúsculas. Si está vacía o
     * null, devuelve Set vacío — y eso significa "permitir TODAS las tablas".
     * Cuidado: Set vacío no es lo mismo que "no permitir ninguna" en esta
     * lógica; el caller tiene que chequear isEmpty() para saber si aplica.
     */
    public java.util.Set<String> getTableWhitelist() {
        if (sqlExecTableWhitelist == null || sqlExecTableWhitelist.isBlank()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : sqlExecTableWhitelist.split(",")) {
            String trimmed = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Construye la URL JDBC según el tipo de BD.
     * No incluye credenciales — esas van aparte en DriverManager.getConnection().
     */
    public String buildJdbcUrl() {
        String base = switch (dbType) {
            case MYSQL,
                 MARIADB   -> "jdbc:" + dbType.name().toLowerCase() + "://" + host + ":" + port + "/" + databaseName;
            case POSTGRES  -> "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + databaseName;
            case ORACLE    -> "jdbc:oracle:thin:@" + host + ":" + port + "/" + databaseName;
        };
        if (extraParams != null && !extraParams.isBlank()) {
            char sep = dbType == DbType.SQLSERVER ? ';' : '?';
            base += sep + extraParams;
        }
        return base;
    }

    public String getDriverClassName() {
        return switch (dbType) {
            case MYSQL     -> "com.mysql.cj.jdbc.Driver";
            case MARIADB   -> "org.mariadb.jdbc.Driver";
            case POSTGRES  -> "org.postgresql.Driver";
            case SQLSERVER -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case ORACLE    -> "oracle.jdbc.OracleDriver";
        };
    }
}
