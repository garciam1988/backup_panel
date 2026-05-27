package app.coincidir.api.apiusage.service;

import app.coincidir.api.apiusage.domain.ApiUsageLog;
import app.coincidir.api.apiusage.repository.ApiUsageLogRepository;
import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.ConversationLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * SessionBreakdownService — arma el detalle "turno por turno" de una sesión
 * del bot conversacional. Sirve para diagnosticar:
 *   - Por qué una conversación fue cara
 *   - Si hubo fallback Haiku→Sonnet
 *   - Si el cache se está rompiendo entre turnos
 *   - Cuánto contribuyó cada turno al costo total
 *
 * <h3>Cómo se arma</h3>
 * Necesitamos cruzar dos tablas que NO tienen FK directa pero comparten una
 * convención de claves (documentada en {@code PanelBotTableController}):
 *
 * <pre>
 *   api_usage_log.session_id   ↔   conversation_log.visitor_id
 * </pre>
 *
 * El frontend identifica al visitante con un UUID que se mantiene durante
 * toda la sesión del navegador. Ese UUID viaja:
 *   - Como {@code visitorId} al guardar el ConversationLog.
 *   - Como {@code sessionId} al guardar cada ApiUsageLog (vía el frontend
 *     cuando reporta consumo).
 *
 * Por eso podemos buscar el ConversationLog haciendo
 * {@code findFirstByVisitorIdOrderByIdDesc(sessionId)}.
 *
 * <h3>Pareado mensaje ↔ llamada API</h3>
 * El ConversationLog guarda los mensajes con su timestamp. El ApiUsageLog
 * guarda su {@code createdAt}. Pareamos cada llamada API con el mensaje del
 * ASISTENTE más cercano en el tiempo: para cada llamada, buscamos el mensaje
 * con {@code role="assistant"} cuyo timestamp esté DENTRO de ±90 segundos
 * de la llamada y elegimos el más cercano.
 *
 * 90 segundos es lo suficientemente ancho para cubrir latencias normales de
 * Anthropic (que pueden llegar a 60s con tools) sin ser tan amplio como para
 * confundir turnos diferentes (las conversaciones humanas tienen pausas
 * mínimas de varios segundos entre mensajes).
 *
 * Si no encontramos match, la llamada queda como "huérfana" — igual aparece
 * en el resultado pero sin el contexto del mensaje. No tiramos error porque
 * eso es esperable cuando el ConversationLog se cerró antes que la llamada
 * (caso raro pero posible — ej: feature='extract_client_data' que dispara
 * después del último mensaje).
 *
 * <h3>Alertas automáticas</h3>
 * Detectamos patrones sospechosos para que el operador vea de un vistazo
 * qué pasó:
 *   - Upgrade Haiku→Sonnet (la sesión cambió de modelo en algún turno).
 *   - Cache writes recurrentes (el cache se está rompiendo turno a turno).
 *   - Output muy largo (alguna respuesta del bot pasó de 2000 tokens).
 *   - Sesión muy larga (>15 turnos = el bot no resolvió eficientemente).
 *
 * Cada alerta tiene severidad ("warn" o "info") y un mensaje accionable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionBreakdownService {

    private final ApiUsageLogRepository usageRepo;
    private final ConversationLogRepository conversationRepo;
    private final ObjectMapper objectMapper;

    /** Ventana de pareado mensaje-llamada (ms a cada lado del timestamp). */
    private static final long MATCH_WINDOW_MS = 90_000L;

    /**
     * Endpoint público (a nivel service) que arma el breakdown completo de
     * una sesión. Si la sesión no tiene llamadas API ni ConversationLog,
     * devuelve un BreakdownDto vacío (no null) para que el frontend pueda
     * mostrar "sin datos" en vez de error 404.
     */
    @Transactional(readOnly = true)
    public BreakdownDto buildBreakdown(String sessionId) {
        BreakdownDto out = new BreakdownDto();
        out.sessionId = sessionId;

        if (sessionId == null || sessionId.isBlank()) {
            out.alerts = new ArrayList<>();
            out.turns = new ArrayList<>();
            return out;
        }

        // 1) Cargar llamadas API de esta sesión, ordenadas cronológicamente.
        List<ApiUsageLog> calls = usageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 2) Cargar el ConversationLog asociado (puede ser null si la
        //    conversación no se cerró todavía o si se perdió por algún motivo).
        Optional<ConversationLog> convOpt =
                conversationRepo.findFirstByVisitorIdOrderByIdDesc(sessionId);

        // 3) Parsear los mensajes si hay conversación.
        List<ParsedMessage> messages = convOpt
                .map(this::parseMessages)
                .orElseGet(ArrayList::new);

        if (convOpt.isPresent()) {
            ConversationLog conv = convOpt.get();
            out.conversationId = conv.getId();
            out.clientFirstName = conv.getClientFirstName();
            out.clientLastName = conv.getClientLastName();
            out.brandName = conv.getBrandName();
            out.startedAt = conv.getStartedAt();
            out.endedAt = conv.getEndedAt();
            out.messageCount = conv.getMessageCount();
            out.closedReason = conv.getClosedReason();
            out.hadReservation = conv.getHadReservation();
        }

        // 4) Construir los "turnos" pareando llamadas con mensajes.
        out.turns = buildTurns(calls, messages);

        // 5) Computar totales.
        out.totalCostUsd = calls.stream()
                .map(c -> c.getCostUsd() != null ? c.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        out.totalCalls = calls.size();
        out.totalInputTokens = sumInt(calls, ApiUsageLog::getInputTokens);
        out.totalOutputTokens = sumInt(calls, ApiUsageLog::getOutputTokens);
        out.totalCacheReadTokens = sumInt(calls, ApiUsageLog::getCacheReadTokens);
        out.totalCacheWriteTokens = sumInt(calls, ApiUsageLog::getCacheWriteTokens);

        // 6) Detectar patrones sospechosos.
        out.alerts = computeAlerts(out, calls);

        return out;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Parsea el array de mensajes del messagesJson. Tolerante a errores: si
     * el JSON es inválido devolvemos lista vacía (el frontend muestra el
     * breakdown sin contenido de mensajes).
     */
    private List<ParsedMessage> parseMessages(ConversationLog conv) {
        List<ParsedMessage> out = new ArrayList<>();
        if (conv.getMessagesJson() == null || conv.getMessagesJson().isBlank()) return out;
        try {
            JsonNode arr = objectMapper.readTree(conv.getMessagesJson());
            if (!arr.isArray()) return out;
            for (JsonNode node : arr) {
                ParsedMessage m = new ParsedMessage();
                m.role = node.path("role").asText("");
                m.content = node.path("content").asText("");
                String tsStr = node.path("ts").asText("");
                if (!tsStr.isBlank()) {
                    try { m.ts = Instant.parse(tsStr); }
                    catch (Exception ignored) {}
                }
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[SessionBreakdown] no se pudo parsear messagesJson de sesión {}: {}",
                    conv.getVisitorId(), e.getMessage());
        }
        return out;
    }

    /**
     * Construye la lista de turnos. Un "turno" es una llamada API (que generó
     * una respuesta del bot) más el contexto: mensaje del usuario que la
     * disparó y mensaje del asistente que produjo.
     *
     * Estrategia:
     *   - Itero las llamadas API en orden cronológico (turno 1, 2, ...).
     *   - Para cada una busco el mensaje del asistente más cercano en tiempo
     *     dentro de la ventana de pareado.
     *   - El mensaje del usuario asociado es el último mensaje "user" ANTES
     *     de esa llamada API. Si no hay (caso raro), queda null.
     */
    private List<TurnDto> buildTurns(List<ApiUsageLog> calls, List<ParsedMessage> messages) {
        List<TurnDto> turns = new ArrayList<>();
        int turnNumber = 0;
        String previousModel = null;

        for (ApiUsageLog call : calls) {
            turnNumber++;
            TurnDto t = new TurnDto();
            t.turnNumber = turnNumber;
            t.callId = call.getId();
            t.ts = call.getCreatedAt();
            t.model = call.getModel();
            t.feature = call.getFeature();
            t.inputTokens = nullSafe(call.getInputTokens());
            t.outputTokens = nullSafe(call.getOutputTokens());
            t.cacheReadTokens = nullSafe(call.getCacheReadTokens());
            t.cacheWriteTokens = nullSafe(call.getCacheWriteTokens());
            t.costUsd = call.getCostUsd() != null ? call.getCostUsd() : BigDecimal.ZERO;
            t.error = call.getError();

            // Detectar cambio de modelo
            if (previousModel != null && !previousModel.equals(call.getModel())) {
                t.modelSwitched = true;
                t.previousModel = previousModel;
            }
            previousModel = call.getModel();

            // Parear mensajes
            ParsedMessage assistantMsg = findClosestAssistant(messages, call.getCreatedAt());
            if (assistantMsg != null) {
                t.assistantText = assistantMsg.content;
                t.assistantTs = assistantMsg.ts;
            }
            ParsedMessage userMsg = findLastUserBefore(messages, call.getCreatedAt());
            if (userMsg != null) {
                t.userText = userMsg.content;
                t.userTs = userMsg.ts;
            }

            // Computar el porcentaje del costo que viene del cache write
            // (señal de "cache rompiéndose"). Si > 50%, este turno está pagando
            // de más por reconstruir el cache.
            long inputTotal = (long) t.inputTokens + t.cacheReadTokens + t.cacheWriteTokens;
            t.cacheWriteRatio = inputTotal > 0
                    ? (double) t.cacheWriteTokens / inputTotal
                    : 0.0;

            turns.add(t);
        }
        return turns;
    }

    /**
     * Mensaje "assistant" más cercano al timestamp de la llamada API dentro
     * de la ventana de pareado.
     */
    private ParsedMessage findClosestAssistant(List<ParsedMessage> messages, Instant callTs) {
        if (callTs == null) return null;
        ParsedMessage best = null;
        long bestDelta = Long.MAX_VALUE;
        for (ParsedMessage m : messages) {
            if (!"assistant".equals(m.role)) continue;
            if (m.ts == null) continue;
            long delta = Math.abs(ChronoUnit.MILLIS.between(callTs, m.ts));
            if (delta > MATCH_WINDOW_MS) continue;
            if (delta < bestDelta) {
                bestDelta = delta;
                best = m;
            }
        }
        return best;
    }

    /**
     * Último mensaje del usuario ANTES del timestamp dado. Si no hay, null.
     */
    private ParsedMessage findLastUserBefore(List<ParsedMessage> messages, Instant callTs) {
        if (callTs == null) return null;
        ParsedMessage best = null;
        for (ParsedMessage m : messages) {
            if (!"user".equals(m.role)) continue;
            if (m.ts == null) continue;
            if (m.ts.isAfter(callTs)) continue;
            // Reemplazamos hasta llegar al más reciente antes de callTs.
            if (best == null || m.ts.isAfter(best.ts)) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Detección de patrones sospechosos. Cada alerta sale con severidad
     * para que el frontend la coloree apropiadamente.
     */
    private List<AlertDto> computeAlerts(BreakdownDto out, List<ApiUsageLog> calls) {
        List<AlertDto> alerts = new ArrayList<>();

        // 1) Upgrade de modelo durante la sesión (Haiku → Sonnet típicamente).
        String firstModel = null;
        String lastModel = null;
        int switchCount = 0;
        Integer switchAtTurn = null;
        int turnIdx = 0;
        for (ApiUsageLog c : calls) {
            turnIdx++;
            if (firstModel == null) firstModel = c.getModel();
            if (lastModel != null && !lastModel.equals(c.getModel())) {
                switchCount++;
                if (switchAtTurn == null) switchAtTurn = turnIdx;
            }
            lastModel = c.getModel();
        }
        if (switchCount > 0 && firstModel != null && lastModel != null) {
            AlertDto a = new AlertDto();
            a.severity = "warn";
            a.icon = "🔀";
            a.title = "Cambio de modelo durante la sesión";
            a.detail = "La sesión arrancó con " + shortModel(firstModel) +
                       " y terminó con " + shortModel(lastModel) +
                       " (cambio en el turno #" + switchAtTurn + "). " +
                       "Esto suele pasar por fallback automático cuando el modelo " +
                       "inicial no logra resolver. El cache del modelo nuevo se " +
                       "construye desde cero a partir de ahí (penalty de ~1 cache write).";
            alerts.add(a);
        }

        // 2) Cache writes recurrentes — el cache no se está reusando entre turnos.
        // Esperamos UN cache write inicial al inicio de la sesión y después
        // muchos cache reads. Si vemos cache writes en >1 turno, algo está mal.
        long turnsWithCacheWrite = calls.stream()
                .filter(c -> c.getCacheWriteTokens() != null && c.getCacheWriteTokens() > 0)
                .count();
        if (turnsWithCacheWrite > 1) {
            AlertDto a = new AlertDto();
            a.severity = "warn";
            a.icon = "⚠️";
            a.title = "Cache rompiéndose entre turnos";
            a.detail = "Detectamos " + turnsWithCacheWrite + " turnos con cache write " +
                       "(esperado: 1 al inicio). Esto significa que el cache de " +
                       "Anthropic se está reconstruyendo en cada turno, lo que infla " +
                       "el costo ~10x. Causas típicas: el system prompt cambió a " +
                       "mitad de sesión, las tools cambiaron, o pasaron >5 minutos " +
                       "entre turnos (el cache de Anthropic dura 5 min). Si el bot " +
                       "tiene reglas que dependen de la hora actual o del estado del " +
                       "carrito, considerá moverlas fuera del system message.";
            alerts.add(a);
        }

        // 3) Output muy largo (algún turno con >2000 tokens de output).
        Integer maxOutput = calls.stream()
                .map(c -> nullSafe(c.getOutputTokens()))
                .max(Comparator.naturalOrder()).orElse(0);
        if (maxOutput > 2000) {
            AlertDto a = new AlertDto();
            a.severity = "info";
            a.icon = "📜";
            a.title = "Respuestas muy largas";
            a.detail = "Algún turno generó " + maxOutput + " tokens de output " +
                       "(~" + (maxOutput / 4) + " palabras). " +
                       "Output es ~5x más caro que input, por eso conviene pedirle " +
                       "al bot que sea más conciso. Revisá el system prompt: ¿está " +
                       "instruyendo a responder con detalle innecesario? Si tiene " +
                       "que listar cosas, considerá agregar 'máx 5 ítems por respuesta'.";
            alerts.add(a);
        }

        // 4) Sesión muy larga (>15 turnos).
        if (calls.size() > 15) {
            AlertDto a = new AlertDto();
            a.severity = "info";
            a.icon = "💬";
            a.title = "Conversación muy larga";
            a.detail = "Esta sesión tuvo " + calls.size() + " turnos. " +
                       "Las conversaciones eficientes resuelven en 5-10 turnos. " +
                       "Revisá el chat: ¿el bot está pidiendo datos uno por uno en " +
                       "vez de varios juntos? ¿hay loops donde el usuario repite la " +
                       "misma intención porque el bot no la entiende?";
            alerts.add(a);
        }

        return alerts;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int sumInt(List<ApiUsageLog> calls, java.util.function.Function<ApiUsageLog, Integer> getter) {
        int sum = 0;
        for (ApiUsageLog c : calls) sum += nullSafe(getter.apply(c));
        return sum;
    }

    private int nullSafe(Integer i) {
        return i == null ? 0 : i;
    }

    private String shortModel(String model) {
        if (model == null) return "(desconocido)";
        if (model.contains("haiku")) return "Haiku";
        if (model.contains("sonnet")) return "Sonnet";
        if (model.contains("opus")) return "Opus";
        return model;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    /** DTO consolidado del breakdown de una sesión. */
    public static class BreakdownDto {
        public String sessionId;
        public Long conversationId;
        public String clientFirstName;
        public String clientLastName;
        public String brandName;
        public Instant startedAt;
        public Instant endedAt;
        public Integer messageCount;
        public String closedReason;
        public Boolean hadReservation;

        public BigDecimal totalCostUsd = BigDecimal.ZERO;
        public int totalCalls;
        public int totalInputTokens;
        public int totalOutputTokens;
        public int totalCacheReadTokens;
        public int totalCacheWriteTokens;

        public List<TurnDto> turns;
        public List<AlertDto> alerts;
    }

    /** DTO de un turno individual (1 llamada API + contexto del mensaje). */
    public static class TurnDto {
        public int turnNumber;
        public Long callId;
        public Instant ts;
        public String model;
        public String feature;
        public int inputTokens;
        public int outputTokens;
        public int cacheReadTokens;
        public int cacheWriteTokens;
        public BigDecimal costUsd = BigDecimal.ZERO;
        public String error;

        /** Texto del mensaje del usuario que originó este turno (puede ser null). */
        public String userText;
        public Instant userTs;
        /** Texto de la respuesta del bot en este turno (puede ser null). */
        public String assistantText;
        public Instant assistantTs;

        /** TRUE si el modelo de este turno difiere del anterior (fallback/upgrade). */
        public boolean modelSwitched;
        public String previousModel;

        /**
         * Fracción del input total que vino como cache_write (0.0 - 1.0).
         * Si es >0.5, este turno pagó cache reconstruction (señal de problema).
         */
        public double cacheWriteRatio;
    }

    /** Alerta automática detectada en la sesión. */
    public static class AlertDto {
        public String severity;  // "info" | "warn"
        public String icon;
        public String title;
        public String detail;
    }

    /** Mensaje parseado del array messages_json del ConversationLog. */
    private static class ParsedMessage {
        String role;     // "user" | "assistant"
        String content;
        Instant ts;
    }
}
