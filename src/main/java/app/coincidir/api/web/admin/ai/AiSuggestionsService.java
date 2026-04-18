package app.coincidir.api.web.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSuggestionsService {

    private final AiGroupAnalyzerTx analyzer;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();

    // Modelo para análisis principal (costo bajo, detecta problemas)
    private static final String MODEL_ANALYSIS = "claude-haiku-4-5-20251001";
    // Modelo para filtro de segunda pasada (mayor precisión, aplica reglas de negocio)
    private static final String MODEL_FILTER = "claude-sonnet-4-6";

    @Async
    public void runAnalysisAsync() {
        if (!running.compareAndSet(false, true)) {
            log.info("[AiSuggestions] Ya hay análisis en curso, saltando");
            return;
        }
        log.info("[AiSuggestions] Iniciando análisis de operaciones...");

        // Carga de grupos en transacción separada (AiGroupAnalyzerTx es un bean distinto)
        List<Long> groupIds = analyzer.loadActiveGroups().stream()
                .map(g -> g.getId())
                .toList();

        log.info("[AiSuggestions] Grupos activos a analizar: {}", groupIds.size());
        int ok = 0, errors = 0;

        for (Long groupId : groupIds) {
            try {
                // Construye contexto JSON con su propia transacción
                String context = analyzer.buildContext(groupId);
                if (context == null) {
                    log.debug("[AiSuggestions] Grupo {} sin servicios, saltando", groupId);
                    continue;
                }

                // Cargar findings suprimidos para esta OP (para inyectar en prompt)
                String suppressedFindings = analyzer.loadSuppressedFindings(groupId);

                // Paso 1: análisis principal
                String claudeResponse = callClaude(context, suppressedFindings);
                if (claudeResponse == null) {
                    errors++;
                    continue;
                }

                // Paso 2: filtro con reglas aprendidas del operador (segunda pasada — siempre activo)
                String learnedRules = analyzer.buildLearnedRulesContext();
                String filtered = filterFindings(claudeResponse, learnedRules);
                String finalResponse = (filtered != null && !filtered.isBlank()) ? filtered : claudeResponse;
                log.info("[AiSuggestions] Grupo {} — filtrado de reglas: {}", groupId, filtered != null ? "aplicado" : "sin reglas");

                // Guarda resultado final
                analyzer.saveResult(groupId, finalResponse, objectMapper);
                ok++;

            } catch (Exception e) {
                errors++;
                log.warn("[AiSuggestions] Error analizando grupo {}: {}", groupId, e.getMessage());
            }
        }
        running.set(false);
        log.info("[AiSuggestions] Análisis completado. OK={} Errores={}", ok, errors);
    }

    private String callClaude(String contextJson, String suppressedFindingsJson) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.error("[AiSuggestions] API key de Anthropic no configurada");
            return null;
        }

        // Bloque de findings suprimidos para esta OP específica
        String suppressedBlock = "";
        if (suppressedFindingsJson != null && !suppressedFindingsJson.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(suppressedFindingsJson);
                if (arr.isArray() && !arr.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("\n=== SITUACIONES YA REVISADAS EN ESTA OPERACIÓN (NO REPORTAR) ===\n");
                    sb.append("El operador ya revisó las siguientes situaciones y las consideró correctas.\n");
                    sb.append("NO las reportes bajo ningún título, descripción o reformulación:\n");
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        sb.append("- ").append(n.asText()).append("\n");
                    }
                    sb.append("=== FIN ===\n");
                    suppressedBlock = sb.toString();
                    log.info("[AiSuggestions] {} findings suprimidos inyectados en prompt", arr.size());
                }
            } catch (Exception e) {
                log.warn("[AiSuggestions] Error parseando suppressed: {}", e.getMessage());
            }
        }

        // Bloque de reglas globales aprendidas
        String learnedRulesBlock = "";
        String learnedRules = analyzer.buildLearnedRulesContext();
        if (learnedRules != null && !learnedRules.isBlank()) {
            learnedRulesBlock = "\n=== PROHIBICIONES ABSOLUTAS — CASOS VALIDADOS POR EL OPERADOR ===\n"
                    + "NUNCA reportar estos tipos de situaciones:\n"
                    + learnedRules
                    + "=== FIN DE PROHIBICIONES ===\n\n";
        }

        String systemPrompt = suppressedBlock + learnedRulesBlock + """
Sos un asistente experto en operaciones de agencia de viajes uruguaya (YES Travel).
Reportas UNICAMENTE errores criticos reales con datos concretos.
REGLA FUNDAMENTAL: ante la duda, NO reportes. Es mucho peor un falso positivo que no detectar algo.

========================
REGLAS DE NEGOCIO DEFINITIVAS — VALIDADAS POR EL OPERADOR:
========================

REGLA 1 — CHECK-IN / CHECK-OUT DE HOTEL:
El horario de check-in es MERAMENTE INFORMATIVO. Indica a partir de que hora la habitacion esta disponible.
NO indica la hora de llegada del pasajero. Es COMPLETAMENTE NORMAL que el pasajero llegue despues del check-in.
NUNCA reportar ningun problema relacionado con horarios de check-in de hotel. JAMAS.
Check-out: solo reportar si el siguiente servicio empieza MAS DE 5 HORAS despues del check-out.

REGLA 2 — CONEXIONES FERRY ↔ VUELO (REGLA MAS IMPORTANTE):
CALCULO: margen = hora_salida_siguiente − hora_llegada_anterior. Solo horas y minutos brutos. FIN.
PROHIBIDO descontar nada (tramites, traslado, check-in aereo). El operador ya tiene eso contemplado en el umbral.

UMBRALES DEFINITIVOS (definidos por el operador tras revision de casos reales):
  A) Ferry llega a Buenos Aires → Vuelo sale desde Aeroparque (AEP):
     REPORTAR ERROR solo si margen < 2 horas 30 minutos.
     Si margen >= 2h30m → NO REPORTAR JAMAS, aunque calcules riesgos operativos.
  B) Vuelo llega a Aeroparque (AEP) → Ferry sale desde Buenos Aires:
     REPORTAR ERROR solo si margen < 2 horas 30 minutos.
     Si margen >= 2h30m → NO REPORTAR JAMAS.
  C) Vuelo llega a Ezeiza (EZE) → Ferry sale desde Buenos Aires:
     REPORTAR ERROR solo si margen < 2 horas.
     Si margen >= 2h → NO REPORTAR.
  D) Vuelo llega a Aeroparque (AEP) → Vuelo sale desde Aeroparque (AEP):
     REPORTAR ERROR solo si margen < 1 hora.
     Si margen >= 1h → NO REPORTAR.

TABLA DE EJEMPLOS REALES (aplicar exactamente igual):
  Ferry llega 09:45, vuelo sale desde AEP 13:00 → margen 3h15m → >= 2h30m → NO REPORTAR
  Ferry llega 09:45, vuelo sale desde AEP 15:25 → margen 5h40m → >= 2h30m → NO REPORTAR
  Ferry llega 09:45, vuelo sale desde AEP 16:13 → margen 6h28m → >= 2h30m → NO REPORTAR
  Vuelo llega AEP 14:50, ferry sale 20:15 → margen 5h25m → >= 2h30m → NO REPORTAR
  Vuelo llega AEP 16:50, ferry sale 21:45 → margen 4h55m → >= 2h30m → NO REPORTAR
  Ferry llega 09:45, vuelo sale desde AEP 11:30 → margen 1h45m → < 2h30m → REPORTAR ERROR
  Vuelo llega AEP 18:00, ferry sale 19:00 → margen 1h → < 2h30m → REPORTAR ERROR

REGLA 3 — DURACION DE VUELOS:
Solo reportar si la duracion es fisicamente imposible (< 20 minutos para cualquier ruta comercial).
Vuelos que cruzan medianoche: la llegada es al DIA SIGUIENTE. Sale 20:48, llega 00:18 = 3h30m. NO es error.

REGLA 4 — FECHAS DE SERVICIOS:
Solo reportar si una fecha esta MAS DE 3 DIAS fuera del rango total del viaje.

REGLA 5 — TIEMPO LIBRE EN DESTINO:
NUNCA reportar tiempo libre entre servicios. El pasajero puede recorrer libremente, descansar, hacer actividades.
Solo es problema si no hay alojamiento para una noche (el viaje dura mas de 1 dia y no hay hotel).

REGLA 6 — OPERACION SIN SERVICIOS COMPLETOS:
Si la operacion tiene pocos servicios (ej: solo ferry o solo vuelo), NO reportar que "falta" algo.
Las operaciones se cargan gradualmente y pueden estar en construccion.

========================
PROHIBICIONES ABSOLUTAS — NUNCA GENERAR ESTOS FINDINGS:
========================
1. Conexion ferry/vuelo con margen >= 2h30m → JAMAS es error segun el operador.
2. Horario de check-in de hotel como problema.
3. "Riesgo operativo" o "margen apretado" cuando el tiempo supera 2h30m.
4. Duraciones de vuelo entre 1h y 5h para rutas sudamericanas.
5. Tiempo libre entre servicios en destino.
6. "Itinerario incompleto" o "faltan servicios".
7. Check-in antes de la llegada del pasajero.

========================

Responde UNICAMENTE con JSON valido, sin texto adicional ni markdown:
{
  "severity": "ERROR" | "OK",
  "summary": "Resumen en una oracion",
  "findings": [
    {
      "type": "ERROR",
      "title": "Titulo breve",
      "description": "Descripcion con datos concretos (fechas, horas, margen calculado)",
      "suggestion": "Accion correctiva especifica"
    }
  ]
}
Si no hay errores reales: {"severity":"OK","summary":"Sin inconsistencias detectadas","findings":[]}.
""";

        String userMessage = "Analiza esta operacion de viaje y detecta inconsistencias:\n\n" + contextJson;

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", MODEL_ANALYSIS);
            requestBody.put("max_tokens", 1500);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", userMessage);
            messages.add(msg);

            requestBody.set("messages", messages);
            requestBody.put("system", systemPrompt);

            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[AiSuggestions] Anthropic respondió {}: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText(null);
                return extractJson(text);
            }
            return null;

        } catch (Exception e) {
            log.error("[AiSuggestions] Error llamando a Claude: {}", e.getMessage());
            return null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public AiStatusDto getStatus() {
        return analyzer.getStatus(running.get(), objectMapper);
    }

    public void suppressFinding(Long suggestionId, String findingTitle) {
        analyzer.suppressFinding(suggestionId, findingTitle);
    }

    public void addNotApplicable(String findingTitle, String findingDescription, String userReason) {
        analyzer.saveLearnedRule(findingTitle, findingDescription, userReason);
    }

    /**
     * Paso 2: llama a Claude con los findings ya generados + reglas aprendidas
     * y le pide que elimine los que coincidan semánticamente con alguna regla.
     * Devuelve el JSON filtrado, o null si no había nada que filtrar.
     */
    private String filterFindings(String claudeJson, String learnedRules) {
        if (learnedRules == null || learnedRules.isBlank()) return null;

        // Si el JSON ya es OK o no tiene findings, no hace falta filtrar
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(claudeJson.trim()
                    .replaceAll("^```[a-z]*\n?", "").replaceAll("```$", "").trim());
            if ("OK".equals(root.path("severity").asText())) return null;
            com.fasterxml.jackson.databind.JsonNode findings = root.path("findings");
            if (!findings.isArray() || findings.isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }

        String filterSystemPrompt = """
Sos el revisor final de un analisis de operaciones de viaje.
Tu tarea: eliminar del JSON cualquier finding que NO cumpla con las reglas del operador.

PROCESO DE DECISION para cada finding:
1. Lee el finding (titulo + descripcion).
2. Extrae el margen de tiempo mencionado (si aplica).
3. Compara contra las reglas del operador.
4. Si el finding viola una regla o no supera el umbral de error → ELIMINAR el finding.
5. Si genuinamente viola la regla del operador → MANTENER.

EJEMPLOS DE APLICACION:
- Finding dice "margen de 5h25m es insuficiente entre vuelo y ferry": el operador dice "solo reportar si < 2h30m". 5h25m > 2h30m → ELIMINAR.
- Finding dice "margen de 3h15m es critico entre ferry y vuelo AEP": operador dice "< 2h30m". 3h15m > 2h30m → ELIMINAR.
- Finding dice "check-in del hotel antes de la llegada": operador dice "check-in es informativo". → ELIMINAR.
- Finding dice "margen de 1h20m entre ferry y vuelo": operador dice "< 2h30m". 1h20m < 2h30m → MANTENER.
- Finding dice "vuelo llega 20h y ferry sale 21h = 1h de margen": 1h < 2h30m → MANTENER.

Si despues de filtrar no quedan findings:
Devuelve exactamente: {"severity":"OK","summary":"Sin inconsistencias detectadas","findings":[]}

Responde UNICAMENTE con el JSON resultante, sin texto adicional ni markdown.
""";

        String userMsg = "FINDINGS A EVALUAR:\n" + claudeJson
                + "\n\nREGLAS DEL OPERADOR (si un finding cae en alguna de estas reglas, eliminarlo):\n"
                + learnedRules;

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", MODEL_FILTER);
            requestBody.put("max_tokens", 1000);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", userMsg);
            messages.add(msg);

            requestBody.set("messages", messages);
            requestBody.put("system", filterSystemPrompt);

            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[AiSuggestions] Filter call respondio {}", response.statusCode());
                return null;
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode contentNode = root.path("content");
            if (contentNode.isArray() && !contentNode.isEmpty()) {
                String filtered = extractJson(contentNode.get(0).path("text").asText(null));
                log.info("[AiSuggestions] Filtrado aplicado OK");
                return filtered;
            }
            return null;
        } catch (Exception e) {
            log.warn("[AiSuggestions] Error en filtrado: {}", e.getMessage());
            return null;
        }
    }

        /**
     * Extrae el bloque JSON de una respuesta que puede contener texto introductorio o markdown.
     * Busca el primer '{' y el último '}' para aislar el objeto JSON.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        // Quitar bloques markdown si los hay
        String clean = text.trim().replaceAll("^```[a-z]*\n?", "").replaceAll("```$", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return clean.substring(start, end + 1);
        }
        return clean;
    }

    public List<AiSuggestionDto> listActive() {
        return analyzer.listActive(objectMapper);
    }

    public void dismiss(Long id) {
        analyzer.dismiss(id);
    }
}
