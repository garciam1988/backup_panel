package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ProactiveRule — regla configurada por el admin para que el bot envíe
 * mensajes proactivos al cliente sin que este escriba primero. Cada regla
 * se asocia a una BotTable (ej: cuentas_mesa) y define un trigger temporal
 * + un mensaje a enviar.
 *
 * Tipos de trigger soportados (triggerType):
 *   - "last_record"    → minutos desde el ÚLTIMO record agregado a la tabla
 *                        (agrupado por contextColumn, ej: minutos desde el
 *                        último pedido de la mesa 5).
 *   - "first_record"   → minutos desde el PRIMER record del contexto
 *                        (ej: cuánto hace que se abrió la mesa 5).
 *   - "last_user_msg"  → minutos desde el último mensaje del usuario en
 *                        la sesión actual.
 *   - "fixed_time"     → todos los días a una hora fija (HH:mm).
 *
 * El job ProactiveRuleJob corre cada 1 minuto y evalúa todas las reglas
 * activas. Si una regla matchea para una combinación (sesión + contexto)
 * que aún no fue disparada, encola el mensaje en proactive_message_queue
 * y registra el disparo en proactive_rule_fired (anti-spam).
 */
@Entity
@Table(name = "proactive_rule",
       indexes = {
           @Index(name = "idx_proactive_rule_table_active", columnList = "table_id, active")
       })
@Data
public class ProactiveRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    /** "last_record" | "first_record" | "last_user_msg" | "fixed_time" */
    @Column(name = "trigger_type", length = 40, nullable = false)
    private String triggerType;

    /** Para los triggers temporales (last_record, first_record, last_user_msg):
     *  cantidad de minutos. Para fixed_time: NULL (se usa triggerTime). */
    @Column(name = "trigger_value")
    private Integer triggerValue;

    /** Para fixed_time: hora en formato "HH:mm" (ej: "22:00"). Para los demás
     *  triggers: NULL. */
    @Column(name = "trigger_time", length = 5)
    private String triggerTime;

    /** Columna de la tabla que define el "contexto" (agrupación). Ej: para
     *  cuentas_mesa sería "numero_mesa" → así no se manda el mismo mensaje
     *  dos veces a la misma mesa. NULL si no aplica (ej: fixed_time global). */
    @Column(name = "context_column", length = 80)
    private String contextColumn;

    /** Mensaje a enviar al cliente. Soporta placeholders {{nombre_columna}}
     *  que se reemplazan con el valor del último record del contexto. */
    @Column(name = "message_template", columnDefinition = "TEXT", nullable = false)
    private String messageTemplate;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Nombre/etiqueta amigable para el admin (ej: "Postre a los 20 min"). */
    @Column(name = "label", length = 200)
    private String label;

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
    void onUpdate() { updatedAt = Instant.now(); }
}
