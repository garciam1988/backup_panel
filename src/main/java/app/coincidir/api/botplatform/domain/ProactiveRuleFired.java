package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ProactiveRuleFired — registro de cada vez que una regla proactiva se
 * disparó para una combinación específica (sessionId + contextKey).
 * Sirve para evitar mandar el mismo mensaje dos veces al mismo contexto.
 *
 * Ejemplo: si la regla "ofrecer postre a los 20 min" se dispara para la
 * mesa 5 (contextKey="5"), guardamos un registro acá. Si en el siguiente
 * tick del job esa misma mesa sigue cumpliendo el trigger, NO se vuelve
 * a disparar porque ya hay un registro previo.
 *
 * Cuando la mesa se cierra (delete de items), el ProactiveRuleService
 * limpia sus marcas para que si la mesa se reabre más tarde, pueda
 * volver a disparar las reglas.
 */
@Entity
@Table(name = "proactive_rule_fired",
       indexes = {
           @Index(name = "idx_pr_fired_lookup", columnList = "rule_id, session_id, context_key")
       })
@Data
public class ProactiveRuleFired {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "session_id", length = 120, nullable = false)
    private String sessionId;

    /** Key del contexto agrupado. Ej: "mesa:5" o "_global" si la regla no
     *  tiene contextColumn (ej: fixed_time). */
    @Column(name = "context_key", length = 120, nullable = false)
    private String contextKey;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @PrePersist
    void onCreate() { if (firedAt == null) firedAt = Instant.now(); }
}
