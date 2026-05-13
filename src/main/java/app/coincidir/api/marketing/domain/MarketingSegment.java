package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * MarketingSegment — Definición guardada de un segmento de clientes.
 *
 * Un segmento es una query construida con un set de filtros sobre los campos
 * de loyalty_customer + loyalty_card + datos derivados (last_visit_days_ago,
 * total_visits, etc.). El admin lo crea desde /marketing > Segmentos y
 * después lo reutiliza para targetear campañas.
 *
 * Estructura de criteria_json:
 *   {
 *     "match": "all",        // "all" = AND, "any" = OR
 *     "filters": [
 *       { "field": "last_visit_days_ago", "op": "gte", "value": 30 },
 *       { "field": "total_visits",        "op": "gte", "value": 3 },
 *       { "field": "current_stamps",      "op": "gte", "value": 5 },
 *       { "field": "birthday_in_days",    "op": "lte", "value": 7 },
 *       { "field": "accepts_whatsapp",    "op": "eq",  "value": true }
 *     ]
 *   }
 *
 * Operadores soportados: eq, neq, gt, gte, lt, lte, in, nin, contains.
 *
 * Cache: estimated_size + last_computed_at evitan re-correr la query cada
 * vez que el admin abre la lista. Se refresca al crear/editar el segmento
 * y opcionalmente vía cron nightly.
 */
@Entity
@Table(name = "marketing_segment")
@Getter @Setter
public class MarketingSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "criteria_json", columnDefinition = "JSON", nullable = false)
    private String criteriaJson;

    /** Cantidad de clientes que matchean (cache). NULL = nunca se computó. */
    @Column(name = "estimated_size")
    private Integer estimatedSize;

    @Column(name = "last_computed_at")
    private Instant lastComputedAt;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
