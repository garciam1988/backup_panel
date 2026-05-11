package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * ConnectorSchemaCache — snapshot del esquema de un BotConnector externo.
 *
 * Se llena llamando a {@code POST /api/admin/bot-connectors/{id}/introspect}.
 * Lo usa el bot (Fase 2) para tener el mapa de la BD en el contexto de Claude
 * sin pegarle a INFORMATION_SCHEMA en cada turno (que sería lento y caro).
 *
 * Una fila por conector (relación 1:1 por connector_id, unique).
 *
 * Campos clave:
 *   - schemaJson: árbol completo {tables: [{name, columns: [...], pks, fks}, ...]}
 *   - llmSummary: texto compacto (~2-5KB) optimizado para system prompt de Claude.
 *                 Se genera al introspectar y se cachea para no recalcular.
 *   - tableCount / columnCount: para mostrar en el admin sin parsear el JSON.
 *   - refreshedAt: cuándo fue la última introspección — el cliente puede
 *                  forzar refresh si cambió el schema.
 */
@Getter
@Setter
@Entity
@Table(name = "connector_schema_cache",
       uniqueConstraints = @UniqueConstraint(name = "uk_connector_schema_cache_connector",
                                             columnNames = "connector_id"))
public class ConnectorSchemaCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    /**
     * JSON completo del schema. Estructura:
     * {
     *   "dbType": "MYSQL",
     *   "tables": [
     *     {
     *       "name": "reservas",
     *       "schema": "public",  // null en MySQL
     *       "columns": [
     *         {"name": "id", "type": "BIGINT", "nullable": false, "pk": true},
     *         {"name": "nombre", "type": "VARCHAR(255)", "nullable": true}
     *       ],
     *       "foreignKeys": [
     *         {"column": "cliente_id", "refTable": "clientes", "refColumn": "id"}
     *       ]
     *     }
     *   ]
     * }
     */
    @Lob
    @Column(name = "schema_json", nullable = false, columnDefinition = "LONGTEXT")
    private String schemaJson;

    /**
     * Resumen plano del schema optimizado para meter en el system prompt de
     * Claude. Formato compacto tipo CREATE TABLE simplificado, sin tipos
     * exactos (ej: "INT" en vez de "BIGINT UNSIGNED"). El bot ve esto y
     * decide qué query armar.
     */
    @Lob
    @Column(name = "llm_summary", columnDefinition = "TEXT")
    private String llmSummary;

    @Column(name = "table_count")
    private Integer tableCount;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "refreshed_at")
    private Instant refreshedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (refreshedAt == null) refreshedAt = now;
    }
}
