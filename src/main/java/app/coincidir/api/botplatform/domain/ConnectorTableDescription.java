package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Descripción en lenguaje natural de una tabla específica de un BotConnector.
 *
 * Diseño:
 *   - Una fila por (connector_id, table_name). Unique constraint en esa tupla.
 *   - El nombre de la tabla se guarda en minúsculas para que las búsquedas
 *     sean determinísticas (la introspección y la whitelist también
 *     normalizan a minúsculas).
 *   - Si una tabla del schema no tiene fila acá, no se inyecta DESC al
 *     llmSummary — comportamiento backward compatible: si nadie describió
 *     nada, el bot ve exactamente lo que veía antes de Fase 5.
 *
 * Alternativa que NO elegimos: guardar las descripciones como un JSON
 * dentro de ConnectorSchemaCache. La razón: el schema se re-escanea
 * frecuentemente (cada vez que el admin toca "Re-escanear") y eso podría
 * pisar las descripciones que el usuario cargó. Tabla separada =
 * persistencia independiente del scan.
 */
@Entity
@Table(
    name = "connector_table_description",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_connector_table_desc",
        columnNames = {"connector_id", "table_name"}
    ),
    indexes = {
        @Index(name = "ix_ctd_connector", columnList = "connector_id")
    }
)
@Getter
@Setter
public class ConnectorTableDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    /**
     * Nombre de la tabla en MINÚSCULAS. La normalización la hace el
     * controller antes de persistir. Si el admin escribe "Pagos" en el
     * frontend, se guarda como "pagos". El llmSummary también compara
     * en minúsculas al inyectar la descripción.
     */
    @Column(name = "table_name", nullable = false, length = 128)
    private String tableName;

    /**
     * Descripción en lenguaje natural. Sin formato especial — Claude lee
     * texto plano y lo procesa bien. Sugerencia para el admin: una o dos
     * oraciones que expliquen el propósito de la tabla + cualquier columna
     * con nombre poco obvio.
     *
     * Ej: "Cada fila es un pago de un cliente. La columna status puede ser
     *      'pendiente', 'confirmado' o 'rechazado'. Solo confirmado cuenta
     *      para facturación."
     */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

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
}
