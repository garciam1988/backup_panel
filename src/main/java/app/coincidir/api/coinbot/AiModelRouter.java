package app.coincidir.api.coinbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AiModelRouter — decide qué modelo LLM usar para cada turno del chat
 * conversacional.
 *
 * Tres estrategias soportadas (configurables vía bot_config.ai_routing_mode):
 *
 *   1) "sonnet_only"   → siempre Sonnet 4.5 (default histórico, máxima calidad).
 *   2) "haiku_only"    → siempre Haiku 4.5 (~3x más barato, menor razonamiento).
 *   3) "smart_routing" → decide modelo POR SESIÓN basado en el primer mensaje
 *                        del usuario:
 *                          - Mensajes simples (saludos, info-request, "no") → Haiku.
 *                          - Mensajes complejos (datos múltiples, modificación,
 *                            varias reservas) → Sonnet.
 *                        Si Haiku falla funcionalmente (HTTP error o response
 *                        vacía), se hace fallback automático a Sonnet y la
 *                        sesión "upgradea" — todos los siguientes turnos de
 *                        esa sesión van a Sonnet.
 *
 * IMPORTANTE — Diseño per-sesión, NO per-turno:
 *   El cache de Anthropic es POR MODELO. Si en una sesión cambiás Haiku→Sonnet
 *   en cualquier turno, Sonnet tiene que reescribir su cache desde cero
 *   (paga 1.25x el input). Por eso decidimos UNA VEZ por sesión y mantenemos.
 *
 *   Decidir en el primer turno es CLAVE: si el primer mensaje es saludo simple
 *   ("Hola buenas tardes") va a Haiku, y aunque después el usuario pase a flujos
 *   complejos, seguimos con Haiku para no perder el cache hit.
 *
 *   El fallback automático cubre los casos donde Haiku no logra resolver: ahí
 *   pagamos el penalty del switch (re-cache) pero solo en sesiones puntuales.
 *
 * Estado en memoria:
 *   Un Map<sessionId, String> recuerda qué modelo se asignó a cada sesión.
 *   TTL implícito: las sesiones se purgan cuando el chat termina (a través del
 *   {@link #releaseSession(String)}). Si el cliente se va sin cerrar, queda
 *   ocupando memoria — pero un sessionId pesa <100 bytes, soportamos millones.
 *
 * NO multi-instance safe: si el backend escala horizontal, el state local
 * se pierde entre instancias. Para multi-instance hay que migrar a Redis.
 */
@Service
@Slf4j
public class AiModelRouter {

    // ── Identificadores de modelos ─────────────────────────────────────
    // Estos son los strings que se pasan en el body JSON a Anthropic.
    // Cambiarlos por las nuevas versiones cuando se haga upgrade global.

    public static final String MODEL_SONNET = "claude-sonnet-4-5";
    public static final String MODEL_HAIKU  = "claude-haiku-4-5-20251001";

    // ── Modos soportados ────────────────────────────────────────────────

    public static final String MODE_SONNET_ONLY   = "sonnet_only";
    public static final String MODE_HAIKU_ONLY    = "haiku_only";
    public static final String MODE_SMART_ROUTING = "smart_routing";

    /** Default cuando bot_config.ai_routing_mode es NULL (caso edge: BD recién
     *  creada, registro sin configurar). En operación normal, todos los bots
     *  tienen la columna seteada explícitamente desde el panel /admin → LLM. */
    private static final String DEFAULT_FALLBACK_MODE = MODE_SONNET_ONLY;

    // ── Memoria de routing per-sesión ───────────────────────────────────
    //
    // Mapea sessionId → modelo elegido. Una vez decidido, todos los
    // siguientes turnos de esa sesión usan el mismo modelo (preserva cache).

    private final Map<String, String> sessionModel = new ConcurrentHashMap<>();

    // ── Heurísticas para clasificar el primer mensaje ───────────────────
    //
    // Estos patterns se aplican al primer mensaje del usuario. Si CUALQUIERA
    // matchea, la sesión se asigna a Sonnet (más capaz). Si NINGUNO matchea,
    // se asigna a Haiku.
    //
    // Por qué Sonnet por default ante duda:
    //   El costo de un FALSO POSITIVO (usar Sonnet cuando alcanzaba Haiku) es
    //   solo plata: 2-3 turnos extra a precio Sonnet.
    //   El costo de un FALSO NEGATIVO (usar Haiku cuando hacía falta Sonnet)
    //   es mucho peor: una reserva mal hecha, un cliente molesto, una
    //   regresión silenciosa que tenemos que descubrir por feedback.

    /** Email en el mensaje sugiere "estoy pasando todos mis datos juntos". */
    private static final Pattern PAT_EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** Teléfono argentino (8+ dígitos seguidos, con o sin guiones/espacios). */
    private static final Pattern PAT_PHONE = Pattern.compile("(?:\\d[\\s-]?){8,}");

    /** Verbos de modificación/cancelación que requieren razonamiento sobre
     *  estado existente. Detecta también typos comunes ("modikficar",
     *  "modficar"). */
    private static final Pattern PAT_MODIFICATION = Pattern.compile(
        "(?i)\\b(modi[fk]+[ic][ar]+|cambi[ar]+|cancel[ar]+|actualizar|corregir|edit[ar]+)\\b"
    );

    /** Múltiples reservas en un solo mensaje (caso Judith) o tono de pedidos
     *  agregados. */
    private static final Pattern PAT_MULTI_RESERVA = Pattern.compile(
        "(?i)\\b(dos|2|tres|3|varias|m[uú]ltiples)\\s+reservas?\\b"
    );

    /** Mensaje con muchas comas o "y" sugiere acumulación de datos. Si tiene
     *  3+ comas o 2+ "y" entre datos, el cliente está pasando datos en bulk. */
    private static final Pattern PAT_MANY_COMMAS = Pattern.compile(",.*,.*,");

    // ── API pública ─────────────────────────────────────────────────────

    /**
     * Decide qué modelo usar para una request del chat.
     *
     * @param sessionId  identificador de la sesión (header X-Session-Id).
     *                   Si es null/blank, se asume "sesión nueva, primer turno"
     *                   y se evalúa contra el body. NO se guarda nada.
     * @param routingMode estrategia del cliente (bot_config.ai_routing_mode).
     *                   Si es null, se usa el default global.
     * @param body       JSON crudo del request (para extraer el primer mensaje
     *                   si es la primera invocación de la sesión).
     * @return el model string a pasar a Anthropic.
     */
    public String chooseModel(String sessionId, String routingMode, String body) {
        String mode = (routingMode == null || routingMode.isBlank())
                ? DEFAULT_FALLBACK_MODE
                : routingMode;

        // Modos triviales: siempre el mismo modelo, sin tocar nada.
        if (MODE_SONNET_ONLY.equals(mode)) return MODEL_SONNET;
        if (MODE_HAIKU_ONLY.equals(mode))  return MODEL_HAIKU;

        // Smart routing: depende del sessionId.
        if (!MODE_SMART_ROUTING.equals(mode)) {
            // Modo desconocido. Default seguro = Sonnet.
            log.warn("[AiModelRouter] modo desconocido '{}', usando sonnet_only", mode);
            return MODEL_SONNET;
        }

        // Sin sessionId no podemos hacer routing per-sesión coherente:
        // evaluamos el mensaje sin guardar. No es ideal pero es defensivo.
        if (sessionId == null || sessionId.isBlank()) {
            return classifyFirstMessage(body);
        }

        // ¿Ya decidimos para esta sesión? Sí → respetar la decisión previa.
        String existing = sessionModel.get(sessionId);
        if (existing != null) {
            return existing;
        }

        // Primer turno de la sesión: clasificar y guardar.
        String chosen = classifyFirstMessage(body);
        sessionModel.put(sessionId, chosen);
        log.info("[AiModelRouter] sesión {} asignada a {} (smart_routing)",
                sessionId, chosen);
        return chosen;
    }

    /**
     * Llamado cuando Haiku falló funcionalmente (HTTP error o response vacía).
     * "Upgradea" la sesión a Sonnet permanentemente — todos los próximos
     * turnos usan Sonnet en vez de Haiku.
     *
     * El cache de Haiku se descarta para esta sesión; Sonnet construirá su
     * propio cache en la primera request siguiente (costo: 1 cache write,
     * ~$0.01 para el system prompt de 6700 tokens).
     */
    public void upgradeSessionToSonnet(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String previous = sessionModel.put(sessionId, MODEL_SONNET);
        if (!MODEL_SONNET.equals(previous)) {
            log.warn("[AiModelRouter] sesión {} UPGRADE {} → Sonnet (fallback)",
                    sessionId, previous);
        }
    }

    /**
     * Libera la memoria de una sesión cerrada. Llamar cuando el cliente
     * cierra el chat o pasa cierto tiempo de inactividad.
     */
    public void releaseSession(String sessionId) {
        if (sessionId == null) return;
        sessionModel.remove(sessionId);
    }

    /**
     * Cuántas sesiones activas hay en memoria. Útil para diagnóstico y
     * dashboards de salud del servicio.
     */
    public int activeSessionCount() {
        return sessionModel.size();
    }

    /**
     * Devuelve el modelo asignado a una sesión (o null si no existe).
     * Útil para logs y audit, no para tomar decisiones (eso lo hace
     * chooseModel).
     */
    public String getSessionModel(String sessionId) {
        return sessionId == null ? null : sessionModel.get(sessionId);
    }

    // ── Lógica interna ──────────────────────────────────────────────────

    /**
     * Clasifica el primer mensaje del usuario y decide modelo.
     * Heurística basada en análisis de 16 conversaciones reales de Brasas:
     *   - Saludos simples ("Hola", "Buenas tardes") → Haiku (~31% de los casos)
     *   - Datos múltiples en un mensaje → Sonnet (~30% de los casos)
     *   - Modificación/cancelación → Sonnet
     *   - Múltiples reservas → Sonnet
     *
     * Si el body no es parseable o no hay mensaje, default a Sonnet (seguro).
     */
    private String classifyFirstMessage(String body) {
        if (body == null || body.isBlank()) return MODEL_SONNET;
        String userText = extractFirstUserMessage(body);
        if (userText == null || userText.isBlank()) return MODEL_SONNET;

        String text = userText.trim();
        int len = text.length();

        // Heurística 1: mensaje MUY corto (< 30 chars) sin datos identificables
        // → siempre Haiku. Esto cubre "Hola", "Buenas tardes", "Quiero reservar".
        // Es el caso de mayor confianza para usar Haiku.
        if (len < 30 && !containsDataPatterns(text)) {
            return MODEL_HAIKU;
        }

        // Heurística 2: contiene datos múltiples (email, teléfono, varias comas)
        // → Sonnet. El usuario está pasando todo de una.
        if (PAT_EMAIL.matcher(text).find()
            || PAT_PHONE.matcher(text).find()
            || PAT_MANY_COMMAS.matcher(text).find()) {
            return MODEL_SONNET;
        }

        // Heurística 3: verbos de modificación o múltiples reservas.
        if (PAT_MODIFICATION.matcher(text).find()
            || PAT_MULTI_RESERVA.matcher(text).find()) {
            return MODEL_SONNET;
        }

        // Heurística 4: mensaje medio (30-80 chars) sin datos complejos
        // → Haiku. Cubre "Hola, quiero reservar para mañana", "8 personas
        // para hoy sábado", etc. — pedidos progresivos donde el bot va a
        // pedir cada dato.
        if (len < 80) {
            return MODEL_HAIKU;
        }

        // Default conservador: mensaje largo o ambiguo → Sonnet.
        return MODEL_SONNET;
    }

    /** Chequea si el texto contiene cualquier patrón de "dato sensible". */
    private boolean containsDataPatterns(String text) {
        return PAT_EMAIL.matcher(text).find()
            || PAT_PHONE.matcher(text).find()
            || PAT_MODIFICATION.matcher(text).find();
    }

    /**
     * Extrae el primer mensaje "user" del body JSON. Si el array messages
     * tiene varios elementos, devolvemos el más reciente del usuario (que
     * en un primer turno es el único user, pero defensivamente buscamos
     * desde el final).
     *
     * El content de un mensaje user puede ser:
     *   - String simple: "Hola"
     *   - Array de bloques: [{type: "text", text: "Hola"}]
     *   - Array con tool_result u otros bloques.
     *
     * Solo extraemos el primer bloque "text".
     */
    private String extractFirstUserMessage(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return null;
            }
            // Buscamos desde el final para tomar el último user (el del turno actual).
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode m = messages.get(i);
                if (!"user".equals(m.path("role").asText(""))) continue;
                JsonNode content = m.get("content");
                if (content == null) continue;
                if (content.isTextual()) return content.asText();
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("text".equals(block.path("type").asText(""))) {
                            return block.path("text").asText("");
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[AiModelRouter] no pude extraer first user message: {}",
                    e.getMessage());
            return null;
        }
    }
}
