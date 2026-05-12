package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Memoria conversacional del bot Manager.
 *
 * Cada fila es un "hecho" extraído de una conversación que el bot puede
 * referenciar en futuros turnos. Permite que el bot deje de comportarse
 * como ChatGPT (cada pregunta es nueva) y se vuelva un asistente real
 * que recuerda el contexto del negocio.
 *
 * Diseño:
 *   - kind='auto' → extraído automáticamente por Haiku después de cada
 *     turno. TTL 30 días. Se acumula sin intervención del usuario.
 *   - kind='permanent' → el usuario dijo explícitamente "recordá esto"
 *     o "guardá esto". No expira nunca.
 *   - kind='deleted' → marcado por comando "olvidá X". No se borra
 *     físicamente (audit trail), solo se filtra al inyectar.
 *
 * Scope: por session_id. Cuando el Gerente abre el chat, tiene una nueva
 * session pero ve las memorias acumuladas (por TTL no por session).
 * El session_id queda para audit: saber en qué conversación se generó cada
 * memoria.
 *
 * Inyección al prompt: al inicio de cada turno, las memorias relevantes
 * (las últimas N permanentes + las últimas M auto no expiradas) se
 * inyectan al system prompt como un bloque "MEMORIA DEL GERENTE".
 *
 * Por qué no usar un store separado (Redis, vector DB): para el volumen
 * esperado (un Gerente, decenas de turnos/día), MySQL es más que suficiente
 * y mantiene todo en un mismo sistema. Si esto escala a múltiples Gerentes
 * o se vuelve un MCP server, se replantea.
 */
@Entity
@Table(
    name = "manager_memory",
    indexes = {
        @Index(name = "ix_mm_kind_expires", columnList = "kind, expires_at"),
        @Index(name = "ix_mm_created", columnList = "created_at"),
        @Index(name = "ix_mm_session", columnList = "session_id")
    }
)
@Getter
@Setter
public class ManagerMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * sessionId del CoinBot — útil para auditar de qué conversación salió
     * cada memoria. NO se usa para filtrar al inyectar — el Gerente debe
     * ver TODAS sus memorias no expiradas independientemente de la sesión.
     */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /**
     * Texto de la memoria. Lenguaje natural, primera persona del Gerente
     * o tercera. Ejemplos:
     *   - "El cliente Pérez ya no va a aceptar más extensiones de pago."
     *   - "En abril 2026 facturamos USD 38.163, baja del 40% vs marzo."
     *   - "Brasil es el destino con mayor caída en Q2."
     *
     * Max 1000 chars — si Haiku genera algo más largo, se trunca.
     */
    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    /**
     * Tipo de memoria:
     *   - 'auto'      → resumen automático de un turno. Expira a 30 días.
     *   - 'permanent' → marcado por el usuario con "recordá esto". No expira.
     *   - 'deleted'   → marcado para olvido por comando del usuario.
     *                   Se mantiene en BD para audit, pero no se inyecta.
     */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /**
     * Texto exacto del turno del usuario que originó esta memoria. Útil
     * para que el bot pueda decir "como me preguntaste sobre X..." y para
     * el admin entender el contexto al revisar.
     */
    @Column(name = "source_user_message", length = 2000)
    private String sourceUserMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Fecha de expiración. Solo aplica a kind='auto'. Para 'permanent'
     * y 'deleted' es null (no caduca).
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Para 'deleted': cuándo se marcó como borrado. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (kind == null) kind = "auto";
    }
}
