package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotTool — Definición de una tool que el bot puede invocar.
 *
 * El admin configura el nombre, descripción (que ve Claude para decidir cuándo
 * usarla), schema de parámetros (JSON Schema de Anthropic), y el SQL template
 * con placeholders nombrados (:param).
 *
 * Cuando Claude invoca la tool, el sistema:
 *   1. Valida que los parámetros enviados cumplan el schema
 *   2. Los pasa como parámetros nombrados al NamedParameterJdbcTemplate
 *   3. El driver JDBC escapa los valores automáticamente → sin SQL injection
 *   4. Ejecuta contra el conector configurado
 *   5. Devuelve las filas (para SELECT) o rowCount (para INSERT/UPDATE/DELETE)
 *   6. Registra una entrada en bot_tool_audit
 *
 * IMPORTANTE: el SQL template es texto libre que el admin escribe. Puede ser
 * cualquier SQL (SELECT, INSERT, UPDATE, DELETE, llamar procedures, etc.).
 * El control de permisos está en la mano del admin: si activa una tool
 * "borrar_todo" con un DELETE sin WHERE, es su responsabilidad.
 */
@Entity
@Table(name = "bot_tool", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bot_tool_name", columnNames = "name")
})
@Getter @Setter
public class BotTool {

    public enum OperationType {
        /** SELECT o cualquier query que devuelva filas. Devuelve List<Map> al bot. */
        QUERY,
        /** INSERT, UPDATE, DELETE, DDL. Devuelve rowsAffected al bot. */
        UPDATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre de la tool — Claude lo usa para invocarla. Debe ser snake_case,
     * solo letras/dígitos/guiones bajos. Ej: "buscar_producto", "registrar_pedido".
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Descripción para Claude: en qué situaciones debe usar esta tool, qué
     * devuelve, cuándo NO usarla. Es el campo más importante — calidad de esta
     * descripción = calidad de cómo el bot la invoca.
     */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /**
     * JSON Schema (formato Anthropic tools input_schema) de los parámetros.
     * Ej:
     * {
     *   "type": "object",
     *   "properties": {
     *     "nombre": {"type": "string", "description": "Nombre del producto"}
     *   },
     *   "required": ["nombre"]
     * }
     */
    @Column(name = "parameters_schema_json", nullable = false, columnDefinition = "TEXT")
    private String parametersSchemaJson;

    /**
     * SQL template con parámetros nombrados. Ej:
     *   SELECT id, nombre, precio FROM productos WHERE nombre LIKE :nombre
     *   INSERT INTO pedidos (cliente_id, total) VALUES (:cliente_id, :total)
     */
    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT")
    private String sqlTemplate;

    /**
     * Tipo de operación. Determina cómo el sistema trata el resultado:
     *   QUERY  → retorna filas (SELECT)
     *   UPDATE → retorna rowsAffected (INSERT/UPDATE/DELETE)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 10)
    private OperationType operationType = OperationType.QUERY;

    /**
     * Conector al que apunta la tool. NO es una @ManyToOne real para evitar
     * complicaciones de ciclos; guardamos el ID y resolvemos a mano.
     */
    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    /** Límite de filas a devolver (solo aplica a QUERY). Default 100. */
    @Column(name = "row_limit")
    private Integer rowLimit = 100;

    /** Si está en false, el bot no la ve. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

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
}
