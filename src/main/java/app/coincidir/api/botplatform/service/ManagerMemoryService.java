package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.ManagerMemory;
import app.coincidir.api.botplatform.repository.ManagerMemoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio que orquesta la memoria conversacional del bot Manager.
 *
 * RESPONSABILIDADES:
 *
 *   1. processTurn(...) → extrae hechos relevantes de un turno usando Haiku
 *      y los persiste como kind='auto' con TTL 30 días. Se llama desde el
 *      frontend después de cada respuesta del bot.
 *
 *   2. detectExplicitCommands(...) → busca patrones "recordá esto", "olvidá X"
 *      en el texto del usuario y maneja esos comandos sin pasar por Haiku.
 *      Se llama ANTES del processTurn normal.
 *
 *   3. buildMemorySection(...) → arma el bloque de texto que se inyecta al
 *      system prompt con las memorias activas. Lo consume el frontend al
 *      construir el prompt de cada turno.
 *
 * DISEÑO ASÍNCRONO:
 *
 * El processTurn corre @Async para no bloquear la respuesta al usuario.
 * El bot responde ya, y mientras tanto Haiku va extrayendo memorias en
 * background. Si la extracción falla, no afecta la conversación.
 *
 * CONFIGURACIÓN:
 *
 * - TTL_DAYS = 30 (memorias automáticas expiran a los 30 días).
 * - MAX_AUTO_TO_INJECT = 15 (cap de auto-memorias en el prompt).
 * - MIN_CONTENT_LENGTH = 30 chars (filtrar memorias triviales tipo "ok").
 *
 * Usa HAIKU porque para resumir 1-2 turnos no necesitamos Sonnet — Haiku
 * es 5x más barato y suficiente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagerMemoryService {

    private final ManagerMemoryRepository repo;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String HAIKU_MODEL = "claude-haiku-4-5-20251001";
    private static final int TTL_DAYS = 30;
    private static final int MAX_AUTO_TO_INJECT = 15;
    private static final int MIN_CONTENT_LENGTH = 30;
    private static final int MAX_CONTENT_LENGTH = 1000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // ─────────────────────────────────────────────────────────────────────
    // 1. INYECCIÓN AL SYSTEM PROMPT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Construye el bloque de "memoria del Gerente" que se inyecta al system
     * prompt antes de cada turno. Estructura:
     *
     *   MEMORIA DEL GERENTE — contexto de conversaciones previas
     *
     *   ▸ Permanente (recordá siempre):
     *     - Hecho 1
     *     - Hecho 2
     *
     *   ▸ Reciente (últimos 30 días):
     *     - [hace 2 días] Hecho 3
     *     - [hace 5 horas] Hecho 4
     *
     * Si no hay memorias, devuelve string vacío (no se inyecta nada).
     * El frontend decide dónde concatenar este bloque al system prompt.
     */
    @Transactional(readOnly = true)
    public String buildMemorySection() {
        List<ManagerMemory> permanent = repo.findPermanent();
        List<ManagerMemory> auto = repo.findAutoActive(
                Instant.now(),
                PageRequest.of(0, MAX_AUTO_TO_INJECT));

        if (permanent.isEmpty() && auto.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("MEMORIA DEL GERENTE — contexto de conversaciones previas\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("Esta es información que recordás de charlas anteriores con el Gerente. ");
        sb.append("Podés referenciarla naturalmente cuando sea relevante (ej: \"como te mencioné ");
        sb.append("antes sobre Brasil...\"). No hace falta mencionarla siempre, solo cuando aporta. ");
        sb.append("Si una pregunta del Gerente contradice algo de la memoria, priorizá los datos ");
        sb.append("frescos de la BD.\n\n");

        if (!permanent.isEmpty()) {
            sb.append("▸ Permanente (recordá siempre):\n");
            for (ManagerMemory m : permanent) {
                sb.append("  - ").append(m.getContent()).append("\n");
            }
            sb.append("\n");
        }

        if (!auto.isEmpty()) {
            sb.append("▸ Reciente (últimos 30 días):\n");
            Instant now = Instant.now();
            for (ManagerMemory m : auto) {
                sb.append("  - [").append(formatRelative(m.getCreatedAt(), now)).append("] ")
                  .append(m.getContent()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /** "hace 2 días", "hace 5 horas", "ahora" — sin librerías externas. */
    private String formatRelative(Instant past, Instant now) {
        long minutes = ChronoUnit.MINUTES.between(past, now);
        if (minutes < 5) return "ahora";
        if (minutes < 60) return "hace " + minutes + " min";
        long hours = ChronoUnit.HOURS.between(past, now);
        if (hours < 24) return "hace " + hours + (hours == 1 ? " hora" : " horas");
        long days = ChronoUnit.DAYS.between(past, now);
        if (days < 7) return "hace " + days + (days == 1 ? " día" : " días");
        long weeks = days / 7;
        return "hace " + weeks + (weeks == 1 ? " semana" : " semanas");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. COMANDOS EXPLÍCITOS (recordá / olvidá)
    // ─────────────────────────────────────────────────────────────────────

    private static final Pattern PAT_REMEMBER = Pattern.compile(
            "(?i)\\b(record(a|á|ate|a esto|á esto|ate esto)|guardá( esto)?|no te olvides( de)?|por favor record)\\b\\s*[:,]?\\s*(.+)",
            Pattern.DOTALL);

    private static final Pattern PAT_FORGET = Pattern.compile(
            "(?i)\\b(olvid(a|á|ate)|borrá|elimin(á|a))\\b\\s+(.+?)(?:[.!?]|$)",
            Pattern.DOTALL);

    /**
     * Detecta y procesa comandos explícitos del usuario.
     *
     * Devuelve null si no detectó ningún comando (el caller debe seguir
     * el flujo normal), o un objeto CommandResult con:
     *   - acknowledgment: texto sugerido para que el bot responda al usuario
     *   - skipNormalProcessing: si true, no llamar a processTurn después
     *
     * Para "olvidá X" se busca match por substring en memorias existentes
     * y se borran (soft). Para "recordá X" se crea memoria permanente.
     */
    public record CommandResult(String acknowledgment, boolean skipNormalProcessing) {}

    @Transactional
    public CommandResult detectExplicitCommands(String userMessage, String sessionId) {
        if (userMessage == null || userMessage.isBlank()) return null;
        String msg = userMessage.trim();

        // "recordá esto: ..." → guardar como permanente
        Matcher rem = PAT_REMEMBER.matcher(msg);
        if (rem.find() && rem.group(rem.groupCount()) != null) {
            String content = rem.group(rem.groupCount()).trim();
            // El usuario dijo "recordá: <X>" — guardamos <X> como memoria permanente
            if (content.length() >= 5) { // mínimo razonable
                ManagerMemory m = new ManagerMemory();
                m.setSessionId(sessionId);
                m.setKind("permanent");
                m.setContent(truncate(content, MAX_CONTENT_LENGTH));
                m.setSourceUserMessage(truncate(msg, 2000));
                repo.save(m);
                log.info("[manager-memory] permanent guardada: {}", truncate(content, 80));
                return new CommandResult(
                        "Listo, lo voy a recordar siempre: \"" + truncate(content, 100) + "\".",
                        true);
            }
        }

        // "olvidá X" → marcar como deleted las que matcheen
        Matcher forget = PAT_FORGET.matcher(msg);
        if (forget.find()) {
            String query = forget.group(forget.groupCount()).trim();
            if (query.length() >= 3) {
                List<ManagerMemory> matches = repo.searchActive(query);
                if (matches.isEmpty()) {
                    return new CommandResult(
                            "No encontré nada en mi memoria que mencione \"" + query + "\". " +
                            "Si querés borrar algo específico, podemos verlo juntos.",
                            true);
                }
                List<Long> ids = matches.stream().map(ManagerMemory::getId).toList();
                int deleted = repo.softDeleteByIds(ids, Instant.now());
                log.info("[manager-memory] olvidadas {} memorias por query: {}", deleted, query);
                return new CommandResult(
                        String.format("Olvidé %d recuerdo%s relacionado%s con \"%s\".",
                                deleted, deleted == 1 ? "" : "s",
                                deleted == 1 ? "" : "s", query),
                        true);
            }
        }

        return null; // sin comando detectado
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. EXTRACCIÓN AUTOMÁTICA DE MEMORIAS (post-turno, async)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Procesa un turno completo (pregunta + respuesta del bot) y guarda los
     * hechos relevantes como memorias automáticas con TTL.
     *
     * @Async = corre en background. No bloquea la respuesta al usuario.
     * Si falla por cualquier motivo (Haiku caído, JSON mal parseado, etc.)
     * se loguea y sigue — la conversación no se afecta.
     *
     * Política para evitar acumulación inútil:
     *   - Solo guarda si el turno tiene contenido "informativo" (la respuesta
     *     del bot incluyó números, fechas, nombres, conclusiones).
     *   - Cap de 3 hechos por turno (no inundar la memoria con minucias).
     *   - Hechos triviales tipo "el usuario saludó" se filtran (Haiku los
     *     evita si el prompt es claro).
     */
    @Async
    public void processTurnAsync(String userMessage, String botResponse, String sessionId) {
        try {
            if (anthropicKey == null || anthropicKey.isBlank()) return;
            if (botResponse == null || botResponse.length() < 50) return; // turno trivial
            extractAndSaveMemories(userMessage, botResponse, sessionId);
        } catch (Exception e) {
            log.warn("[manager-memory] processTurnAsync falló (no afecta conversación): {}", e.getMessage());
        }
    }

    @Transactional
    public void extractAndSaveMemories(String userMessage, String botResponse, String sessionId) throws Exception {
        String prompt = buildExtractionPrompt(userMessage, botResponse);

        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", HAIKU_MODEL);
        body.put("max_tokens", 600);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            log.warn("[manager-memory] Haiku status {} body: {}", resp.statusCode(), resp.body());
            return;
        }

        // Extraer texto de la respuesta
        JsonNode root = jsonMapper.readTree(resp.body());
        StringBuilder text = new StringBuilder();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText(""));
                }
            }
        }
        String raw = text.toString().trim();

        // Limpiar markdown fences
        if (raw.startsWith("```")) {
            int firstNl = raw.indexOf('\n');
            if (firstNl >= 0) raw = raw.substring(firstNl + 1);
            int lastFence = raw.lastIndexOf("```");
            if (lastFence >= 0) raw = raw.substring(0, lastFence);
            raw = raw.trim();
        }

        // Parsear JSON
        JsonNode parsed = jsonMapper.readTree(raw);
        JsonNode facts = parsed.path("facts");
        if (!facts.isArray() || facts.size() == 0) {
            log.debug("[manager-memory] Haiku no extrajo facts del turno");
            return;
        }

        Instant now = Instant.now();
        Instant expires = now.plus(TTL_DAYS, ChronoUnit.DAYS);
        int saved = 0;
        for (JsonNode f : facts) {
            String fact = f.asText("").trim();
            if (fact.length() < MIN_CONTENT_LENGTH) continue;
            ManagerMemory m = new ManagerMemory();
            m.setSessionId(sessionId);
            m.setKind("auto");
            m.setContent(truncate(fact, MAX_CONTENT_LENGTH));
            m.setSourceUserMessage(truncate(userMessage, 2000));
            m.setExpiresAt(expires);
            repo.save(m);
            saved++;
            if (saved >= 3) break; // cap por turno
        }
        if (saved > 0) {
            log.info("[manager-memory] guardadas {} memorias auto de session {}", saved, sessionId);
        }
    }

    private String buildExtractionPrompt(String userMessage, String botResponse) {
        return """
            Sos un asistente que extrae hechos memorables de conversaciones con el Gerente
            de una agencia de viajes (YES Travel). Tu tarea es identificar entre 0 y 3
            HECHOS que valgan la pena recordar para futuros turnos.
            
            Un buen hecho:
            - Es información concreta y verificable (números, fechas, nombres).
            - Es relevante para análisis ejecutivo (no minucias operativas).
            - Es contextual: cosas que el Gerente "ya sabe" que se le dijeron.
            
            Ejemplos BUENOS:
            - "En abril 2026 facturamos USD 38.163, una caída del 40% vs marzo."
            - "Brasil es el destino con mayor caída en Q2 (-65%)."
            - "El cliente Pérez tiene 3 cuotas vencidas por USD 1.200 total."
            
            Ejemplos MALOS (NO guardar):
            - "El usuario preguntó sobre las ventas."
            - "Le mostré un ranking."
            - "Le dije que no había datos." (obvio, no aporta)
            
            Si la conversación es trivial (saludo, comando, pregunta sin respuesta de
            datos), devolvé "facts": [].
            
            ENTRADA — turno reciente:
            
            Pregunta del Gerente:
            %s
            
            Respuesta del bot:
            %s
            
            SALIDA — JSON con esta forma exacta (sin markdown, sin texto adicional):
            {"facts":["hecho 1","hecho 2","hecho 3"]}
            
            Máximo 3 hechos. Cada uno entre 30 y 300 caracteres. En español rioplatense.
            Si no hay nada memorable, devolvé {"facts":[]}.
            """.formatted(
                    truncate(userMessage == null ? "" : userMessage, 1500),
                    truncate(botResponse == null ? "" : botResponse, 3000)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
