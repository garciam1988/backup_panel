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
