package app.coincidir.api.web.admin.ai;

import app.coincidir.api.domain.AirFlightAlert;
import app.coincidir.api.repository.AirFlightAlertRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AirFlightAlertService {

    private final AirFlightAlertRepository alertRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastRunAt = null;
    private volatile String lastRunStatus = "NEVER";

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    @Value("${coincidir.aerodatabox-key:}")
    private String aerodataboxKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String AERODATABOX_URL = "https://aerodatabox.p.rapidapi.com/flights/number";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();

    /** Patrón de número de vuelo IATA: 2 letras + 1-4 dígitos (ej: AR1130, FO210, JA1234) */
    private static final Pattern FLIGHT_IATA_PATTERN = Pattern.compile("^[A-Z]{2}\\d{1,4}[A-Z]?$");

    // -----------------------------------------------------------------------
    //  API pública
    // -----------------------------------------------------------------------

    public AirFlightAlertStatusDto getStatus() {
        try { alertRepo.fixNullDismissed(); } catch (Exception ignored) {}
        long total = alertRepo.count();
        long issues = alertRepo.countActiveIssues();
        return new AirFlightAlertStatusDto(running.get(), lastRunAt, lastRunStatus, (int) total, (int) issues);
    }

    public List<AirFlightAlertDto> listAll() {
        return alertRepo.findAllVisible()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public List<AirFlightAlertDto> listIssues() {
        return alertRepo.findActiveIssues()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public void marcarVisto(Long id) {
        alertRepo.findById(id).ifPresent(a -> {
            a.setDismissed(true);
            alertRepo.save(a);
        });
    }

    public void ignorarPermanentemente(Long id) {
        alertRepo.findById(id).ifPresent(a -> {
            a.setIgnoredPermanently(true);
            a.setDismissed(true);
            alertRepo.save(a);
        });
    }

    public long countActiveIssues() {
        try { alertRepo.fixNullDismissed(); } catch (Exception ignored) {}
        return alertRepo.countActiveIssues();
    }

    @Async
    public void runAsync() {
        if (!running.compareAndSet(false, true)) {
            log.info("[AirFlightAlert] Análisis ya en curso, saltando");
            return;
        }
        log.info("[AirFlightAlert] Iniciando análisis de vuelos emitidos...");
        try {
            // Corregir NULLs en dismissed (filas creadas antes de la migración)
            try { alertRepo.fixNullDismissed(); } catch (Exception ignored) {}
            run();
            lastRunStatus = "OK";
        } catch (Exception e) {
            lastRunStatus = "ERROR: " + e.getMessage();
            log.error("[AirFlightAlert] Error en análisis: {}", e.getMessage(), e);
        } finally {
            lastRunAt = Instant.now();
            running.set(false);
        }
    }

    // -----------------------------------------------------------------------
    //  Lógica principal
    // -----------------------------------------------------------------------

    private void run() {
        // 1. Cargar vuelos EMITIDOS con salida en los próximos 3 días
        List<Map<String, Object>> rows = loadEmittedFlights();
        log.info("[AirFlightAlert] Vuelos EMITIDOS próximos 72h: {}", rows.size());

        if (rows.isEmpty()) {
            log.info("[AirFlightAlert] Sin vuelos a analizar.");
            return;
        }

        // 2. Para cada vuelo, consultar AeroDataBox (si hay número de vuelo válido)
        //    Cache por (flightNumber, date) para no repetir llamadas
        Map<String, JsonNode> aviationCache = new LinkedHashMap<>();
        List<FlightInfo> flights = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            FlightInfo fi = FlightInfo.from(row);
            flights.add(fi);

            String fn = fi.flightNumber; // ya viene de service_payment_record, en MAYÚSCULAS
            if (fn != null && !fn.isBlank() && fi.departureDate != null) {
                String cacheKey = fn + "_" + fi.departureDate;
                if (!aviationCache.containsKey(cacheKey)) {
                    JsonNode avData = queryAeroDataBox(fn, fi.departureDate);
                    aviationCache.put(cacheKey, avData);
                }
                fi.aviationCacheKey = cacheKey;
            }
        }

        // 3. Llamar a Claude AI con todos los datos
        String aiResultJson = callClaude(flights, aviationCache);

        // 4. Parsear y guardar resultados
        saveResults(flights, aviationCache, aiResultJson);
        log.info("[AirFlightAlert] Análisis finalizado. {} vuelos procesados.", flights.size());
    }

    // -----------------------------------------------------------------------
    //  Carga de vuelos EMITIDOS
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> loadEmittedFlights() {
        // Trae el primer flight_number no nulo registrado en los pagos del servicio aéreo
        String sql = "SELECT " +
                "gas.id AS air_service_id, " +
                "gas.group_id, " +
                "tg.destination AS group_destination, " +
                "gas.airline, " +
                "gas.reservation_code, " +
                "(" +
                "  SELECT UPPER(TRIM(" +
                "    CASE WHEN spr.flight_number LIKE '%-%' " +
                "         THEN SUBSTRING(spr.flight_number, LOCATE('-', spr.flight_number) + 1) " +
                "         ELSE spr.flight_number " +
                "    END" +
                "  )) " +
                "  FROM service_payment_record spr " +
                "  JOIN service_payment_plan spp ON spp.id = spr.plan_id " +
                "  WHERE spp.menu_item_id = gsmi.id " +
                "    AND spr.flight_number IS NOT NULL " +
                "    AND TRIM(spr.flight_number) != '' " +
                "  ORDER BY spr.id ASC " +
                "  LIMIT 1 " +
                ") AS flight_number, " +
                "gas.departure_date, " +
                "gas.departure_time, " +
                "gas.departure_arrival_time, " +
                "gas.origin, " +
                "gas.destination, " +
                "gsmi.id AS menu_item_id, " +
                "gsmi.display_name AS menu_item_display_name " +
                "FROM group_air_service gas " +
                "JOIN group_service_menu_item gsmi ON gsmi.id = gas.menu_item_id " +
                "LEFT JOIN travel_group tg ON tg.id = gas.group_id " +
                "WHERE gsmi.operation_status = 'EMITIDO' " +
                "  AND gas.departure_date >= CURDATE() " +
                "  AND gas.departure_date <= DATE_ADD(CURDATE(), INTERVAL 3 DAY) " +
                "ORDER BY gas.departure_date, gas.departure_time";
        return jdbc.queryForList(sql);
    }

    // -----------------------------------------------------------------------
    //  AeroDataBox
    // -----------------------------------------------------------------------

    /** Intenta extraer un número de vuelo IATA del código de reserva. */
    private String normalizeFlightNumber(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return null;
        String candidate = rawCode.trim().toUpperCase().replaceAll("\\s+", "");
        return FLIGHT_IATA_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * Consulta AeroDataBox por número de vuelo + fecha.
     * Endpoint: GET /flights/number/{flightNumber}/{date}
     * Status posibles: Unknown, Expected, EnRoute, CheckIn, GateClosed, Departed,
     *                  Delayed, Approaching, Arrived, Canceled, Diverted, CanceledUncertain
     */
    private JsonNode queryAeroDataBox(String flightNumber, LocalDate date) {
        if (aerodataboxKey == null || aerodataboxKey.isBlank()) {
            log.warn("[AirFlightAlert] AERODATABOX_KEY no configurada, saltando consulta para {}", flightNumber);
            return null;
        }
        try {
            String url = AERODATABOX_URL + "/" +
                    URLEncoder.encode(flightNumber, StandardCharsets.UTF_8) + "/" +
                    date.format(DateTimeFormatter.ISO_LOCAL_DATE);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-RapidAPI-Key", aerodataboxKey)
                    .header("X-RapidAPI-Host", "aerodatabox.p.rapidapi.com")
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(res.body());
                // AeroDataBox devuelve array directo o objeto con "items"
                JsonNode arr = null;
                if (node.isArray()) {
                    arr = node;
                } else if (node.has("items") && node.get("items").isArray()) {
                    arr = node.get("items");
                }
                if (arr != null && !arr.isEmpty()) {
                    log.info("[AirFlightAlert] AeroDataBox: {} resultado(s) para vuelo {}/{}",
                            arr.size(), flightNumber, date);
                    return arr.get(0);
                } else {
                    log.info("[AirFlightAlert] AeroDataBox: sin datos para vuelo {}/{}", flightNumber, date);
                    return null;
                }
            } else if (res.statusCode() == 404) {
                log.info("[AirFlightAlert] AeroDataBox: vuelo {}/{} no encontrado (404)", flightNumber, date);
                return null;
            } else {
                log.warn("[AirFlightAlert] AeroDataBox HTTP {}: {}", res.statusCode(),
                        res.body().substring(0, Math.min(200, res.body().length())));
                return null;
            }
        } catch (Exception e) {
            log.warn("[AirFlightAlert] Error consultando AeroDataBox para {}: {}", flightNumber, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Claude AI
    // -----------------------------------------------------------------------

    private String callClaude(List<FlightInfo> flights, Map<String, JsonNode> aviationCache) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.warn("[AirFlightAlert] Anthropic key no configurada, saltando análisis IA");
            return null;
        }
        try {
            ArrayNode flightsArray = objectMapper.createArrayNode();
            for (FlightInfo fi : flights) {
                ObjectNode fn = objectMapper.createObjectNode();
                fn.put("menuItemId", fi.menuItemId);
                fn.put("groupId", fi.groupId);
                fn.put("groupDestination", fi.groupDestination != null ? fi.groupDestination : "");
                fn.put("menuItemDisplayName", fi.menuItemDisplayName != null ? fi.menuItemDisplayName : "");
                fn.put("airline", fi.airline != null ? fi.airline : "");
                fn.put("reservationCode", fi.reservationCode != null ? fi.reservationCode : "");
                fn.put("flightNumber", fi.flightNumber != null ? fi.flightNumber : "");
                fn.put("departureDate", fi.departureDate != null ? fi.departureDate.toString() : "");
                fn.put("departureTime", fi.departureTime != null ? fi.departureTime : "");
                fn.put("origin", fi.origin != null ? fi.origin : "");
                fn.put("destination", fi.destination != null ? fi.destination : "");

                // Datos de AeroDataBox si están disponibles
                if (fi.aviationCacheKey != null) {
                    JsonNode avData = aviationCache.get(fi.aviationCacheKey);
                    if (avData != null) {
                        fn.put("aviationDataFound", true);
                        fn.put("aviationStatus", getTextSafe(avData, "status"));
                        int delay = getDelay(avData);
                        if (delay > 0) fn.put("aviationDelayMinutes", delay);
                        // Agrega info relevante de departure/arrival
                        if (avData.has("departure")) {
                            JsonNode dep = avData.get("departure");
                            fn.put("aviationDepDelay", dep.path("delay").asInt(0));
                            // AeroDataBox: hora real en actualTime.local
                            if (dep.has("actualTime")) {
                                fn.put("aviationDepActual", dep.path("actualTime").path("local").asText(""));
                            }
                            // AeroDataBox: hora programada en scheduledTime.local
                            if (dep.has("scheduledTime")) {
                                fn.put("aviationDepScheduled", dep.path("scheduledTime").path("local").asText(""));
                            }
                        }
                        if (avData.has("arrival")) {
                            JsonNode arr = avData.get("arrival");
                            fn.put("aviationArrDelay", arr.path("delay").asInt(0));
                            if (arr.has("actualTime")) {
                                fn.put("aviationArrActual", arr.path("actualTime").path("local").asText(""));
                            }
                        }
                    } else {
                        fn.put("aviationDataFound", false);
                        fn.put("aviationNote", "Sin datos en AeroDataBox para este número de vuelo");
                    }
                } else {
                    fn.put("aviationDataFound", false);
                    fn.put("aviationNote", "Número de vuelo no identificable — verificar manualmente");
                }
                flightsArray.add(fn);
            }

            String contextJson = objectMapper.writeValueAsString(flightsArray);

            String systemPrompt = """
Sos un asistente experto en operaciones de una agencia de viajes argentina/uruguaya (YES Travel / Coincidir).
Analizás vuelos emitidos (estado EMITIDO) que están próximos a partir en los siguientes 3 días.

Las aerolíneas relevantes son: Aerolíneas Argentinas (AR), Flybondi (FO), JETSmart (JA).

Tu tarea es analizar la información de cada vuelo, incluyendo los datos de AeroDataBox cuando estén disponibles,
y determinar si hay algún problema real que el operador deba conocer con urgencia.

Problemas relevantes a detectar:
- Vuelo cancelado (aviationStatus = "cancelled")
- Vuelo desviado (aviationStatus = "diverted")
- Vuelo incidentado (aviationStatus = "incident")
- Demora grave: más de 120 minutos de delay (aviationDelayMinutes > 120)
- Demora moderada: entre 60 y 120 minutos

Si aviationDataFound = false y no hay número de vuelo identificable, indicá que no se pudo verificar.
Si aviationDataFound = false pero SÍ hay número de vuelo, es posible que el vuelo sea futuro y aún no figure en AeroDataBox.
En ese caso, indicá severity: "OK" y que no se detectaron problemas activos.

FORMATO DE RESPUESTA:
Respondé EXCLUSIVAMENTE con un array JSON válido. Sin markdown, sin texto previo ni posterior.
Cada elemento del array debe tener exactamente estos campos:
{
  "menuItemId": <número>,
  "hasIssue": <true|false>,
  "severity": "<OK|WARNING|ERROR>",
  "aviationStatus": "<estado o null>",
  "aviationDelayMinutes": <número o null>,
  "aviationDataFound": <true|false>,
  "summary": "<resumen en 1 línea, máx 120 caracteres>",
  "suggestion": "<sugerencia accionable para el operador, máx 200 caracteres>"
}

Si severity = OK, hasIssue debe ser false.
Si severity = WARNING o ERROR, hasIssue debe ser true.
""";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", "claude-opus-4-6");
            requestBody.put("max_tokens", 2000);
            ObjectNode systemNode = objectMapper.createObjectNode();
            systemNode.put("type", "text");
            systemNode.put("text", systemPrompt);
            requestBody.set("system", objectMapper.createArrayNode().add(systemNode));
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            ObjectNode userContent = objectMapper.createObjectNode();
            userContent.put("type", "text");
            userContent.put("text", "Analizá estos vuelos emitidos próximos:\n" + contextJson);
            userMsg.set("content", objectMapper.createArrayNode().add(userContent));
            messages.add(userMsg);
            requestBody.set("messages", messages);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode parsed = objectMapper.readTree(res.body());
                return parsed.path("content").path(0).path("text").asText();
            } else {
                log.warn("[AirFlightAlert] Claude API HTTP {}: {}", res.statusCode(),
                        res.body().substring(0, Math.min(300, res.body().length())));
                return null;
            }
        } catch (Exception e) {
            log.error("[AirFlightAlert] Error llamando a Claude: {}", e.getMessage(), e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Persistencia de resultados
    // -----------------------------------------------------------------------

    private void saveResults(List<FlightInfo> flights, Map<String, JsonNode> aviationCache, String aiResultJson) {
        // Parsear respuesta de Claude
        Map<Long, JsonNode> aiByMenuItemId = new HashMap<>();
        if (aiResultJson != null && !aiResultJson.isBlank()) {
            try {
                String clean = aiResultJson.trim();
                if (clean.startsWith("```")) {
                    clean = clean.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
                }
                JsonNode arr = objectMapper.readTree(clean);
                if (arr.isArray()) {
                    for (JsonNode item : arr) {
                        long mid = item.path("menuItemId").asLong(0);
                        if (mid > 0) aiByMenuItemId.put(mid, item);
                    }
                }
            } catch (Exception e) {
                log.warn("[AirFlightAlert] Error parseando respuesta de Claude: {}", e.getMessage());
            }
        }

        Instant now = Instant.now();

        for (FlightInfo fi : flights) {
            try {
                // Buscar o crear el registro
                String uniKey = fi.menuItemId + "_" + (fi.departureDate != null ? fi.departureDate : "");
                AirFlightAlert alert = alertRepo.findByUniqueKey(uniKey).orElseGet(AirFlightAlert::new);
                alert.setUniqueKey(uniKey);
                alert.setGroupId(fi.groupId);
                alert.setMenuItemId(fi.menuItemId);
                alert.setGroupDestination(fi.groupDestination);
                alert.setMenuItemDisplayName(fi.menuItemDisplayName);
                alert.setAirline(fi.airline);
                alert.setFlightNumber(fi.flightNumber);
                alert.setDepartureDate(fi.departureDate);
                alert.setDepartureTime(fi.departureTime);
                alert.setOrigin(fi.origin);
                alert.setDestination(fi.destination);
                alert.setLastCheckedAt(now);

                // Datos AeroDataBox
                boolean avFound = false;
                JsonNode avData = null;
                if (fi.aviationCacheKey != null) {
                    avData = aviationCache.get(fi.aviationCacheKey);
                    if (avData != null) {
                        avFound = true;
                        alert.setAviationStatus(getTextSafe(avData, "status"));
                        int delay = getDelay(avData);
                        alert.setAviationDelayMinutes(delay > 0 ? delay : null);
                        // Guardar tiempos reales reportados para comparación futura
                        alert.setAvActualDepTime(extractActualHHmm(avData, "departure"));
                        alert.setAvActualArrTime(extractActualHHmm(avData, "arrival"));
                    }
                }
                alert.setAviationDataFound(avFound);

                // Datos de Claude AI
                String prevSeverity = alert.getAiSeverity();
                JsonNode aiNode = aiByMenuItemId.get(fi.menuItemId);
                if (aiNode != null) {
                    String newSeverity = aiNode.path("severity").asText("OK");
                    boolean newHasIssue = aiNode.path("hasIssue").asBoolean(false);
                    alert.setAiSeverity(newSeverity);
                    alert.setAiSummary(aiNode.path("summary").asText(""));
                    alert.setAiSuggestion(aiNode.path("suggestion").asText(""));
                    alert.setHasIssue(newHasIssue);
                    if (aiNode.has("aviationStatus") && !aiNode.get("aviationStatus").isNull()) {
                        alert.setAviationStatus(aiNode.path("aviationStatus").asText(alert.getAviationStatus()));
                    }
                    if (aiNode.has("aviationDelayMinutes") && !aiNode.get("aviationDelayMinutes").isNull()) {
                        alert.setAviationDelayMinutes(aiNode.path("aviationDelayMinutes").asInt(0));
                    }
                } else {
                    // Sin análisis AI: marcar según AeroDataBox
                    String avStatus = alert.getAviationStatus();
                    boolean issue = "Canceled".equalsIgnoreCase(avStatus)
                            || "CanceledUncertain".equalsIgnoreCase(avStatus)
                            || "Diverted".equalsIgnoreCase(avStatus)
                            || "Delayed".equalsIgnoreCase(avStatus);
                    alert.setHasIssue(issue);
                    alert.setAiSeverity(issue ? "ERROR" : "OK");
                    alert.setAiSummary(issue ? "Problema detectado por AeroDataBox: " + avStatus : "Sin problemas detectados");
                    alert.setAiSuggestion(issue ? "Verificar estado del vuelo y contactar aerolínea." : "");
                }

                // ── Lógica de dismissed ──
                // Si está ignorado permanentemente, nunca tocar dismissed
                if (!Boolean.TRUE.equals(alert.getIgnoredPermanently())) {
                    if (!alert.isHasIssue()) {
                        // Issue resuelto → resetear dismissed
                        alert.setDismissed(false);
                    } else if (severityLevel(alert.getAiSeverity()) > severityLevel(prevSeverity)) {
                        // Escaló de severidad → mostrar de nuevo aunque haya sido visto
                        alert.setDismissed(false);
                    }
                }

                // ── Si el operador ya actualizó los tiempos para demoras → resolver ──
                if (alert.isHasIssue() && avData != null) {
                    String avStatus = getTextSafe(avData, "status");
                    if (operatorAlreadyUpdatedTimes(fi, avData, avStatus)) {
                        alert.setHasIssue(false);
                        alert.setAiSeverity("OK");
                        alert.setAiSummary("Horarios actualizados en sistema. Sin problema activo.");
                        alert.setAiSuggestion("");
                        alert.setDismissed(false);
                        log.info("[AirFlightAlert] Vuelo {} - tiempos corregidos por operador.", fi.flightNumber);
                    }
                }

                alertRepo.save(alert);
            } catch (Exception e) {
                log.warn("[AirFlightAlert] Error guardando alerta para menuItem {}: {}", fi.menuItemId, e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static String getTextSafe(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        return v.isNull() ? null : v.asText();
    }

    private static int getDelay(JsonNode flightData) {
        if (flightData == null) return 0;
        int depDelay = flightData.path("departure").path("delay").asInt(0);
        int arrDelay = flightData.path("arrival").path("delay").asInt(0);
        return Math.max(depDelay, arrDelay);
    }



    private static int severityLevel(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase()) {
            case "OK" -> 0;
            case "WARNING" -> 1;
            case "ERROR" -> 2;
            default -> 0;
        };
    }

    // -----------------------------------------------------------------------
    //  Helpers de comparación de tiempos con AeroDataBox
    // -----------------------------------------------------------------------

    /**
     * Extrae HH:mm del campo actualTime.local de AeroDataBox para el segmento dado.
     * AeroDataBox devuelve: "2026-04-15 01:50+03:00" → extraemos "01:50"
     */
    private static String extractActualHHmm(JsonNode avData, String segment) {
        if (avData == null) return null;
        JsonNode seg = avData.path(segment);
        // Intentar primero actualTime, luego revisedTime
        String local = seg.path("actualTime").path("local").asText(null);
        if (local == null || local.isBlank()) {
            local = seg.path("revisedTime").path("local").asText(null);
        }
        if (local == null || local.isBlank()) return null;
        // Formato: "2026-04-15 01:50+03:00" → buscar después del espacio
        int sp = local.indexOf(' ');
        if (sp >= 0 && local.length() >= sp + 6) {
            return local.substring(sp + 1, sp + 6); // "01:50"
        }
        return null;
    }

    /** Normaliza a HH:mm (quita segundos si los tiene) */
    private static String normalizeToHHmm(String time) {
        if (time == null || time.isBlank()) return null;
        String t = time.trim();
        // "20:20:00" → "20:20" / "20:20" → "20:20"
        return t.length() >= 5 ? t.substring(0, 5) : null;
    }

    /**
     * Determina si el operador ya actualizó los tiempos en el sistema para reflejar
     * el cambio reportado por AeroDataBox.
     * Solo aplica para vuelos demorados (Delayed). Para cancelados/desviados retorna false.
     */
    private static boolean operatorAlreadyUpdatedTimes(FlightInfo fi, JsonNode avData, String avStatus) {
        if (avData == null || avStatus == null) return false;
        // Solo aplica para demoras
        if (!"Delayed".equalsIgnoreCase(avStatus)) return false;

        String avDepActual = extractActualHHmm(avData, "departure");
        String avArrActual = extractActualHHmm(avData, "arrival");

        String sysDepTime = normalizeToHHmm(fi.departureTime);
        String sysArrTime = normalizeToHHmm(fi.departureArrivalTime);

        // Si no hay tiempos reales de AeroDataBox, no podemos comparar
        if (avDepActual == null && avArrActual == null) return false;

        // Si hay tiempo de salida real: el sistema debe coincidir
        if (avDepActual != null && !avDepActual.equals(sysDepTime)) return false;

        // Si hay tiempo de arribo real: el sistema debe coincidir
        if (avArrActual != null && sysArrTime != null && !avArrActual.equals(sysArrTime)) return false;

        return true; // todos los tiempos disponibles coinciden
    }

    private AirFlightAlertDto toDto(AirFlightAlert a) {
        return new AirFlightAlertDto(
                a.getId(), a.getGroupId(), a.getMenuItemId(),
                a.getGroupDestination(), a.getMenuItemDisplayName(),
                a.getFlightNumber(), a.getAirline(),
                a.getDepartureDate(), a.getDepartureTime(),
                a.getOrigin(), a.getDestination(),
                a.getAviationStatus(), a.getAviationDelayMinutes(),
                a.isAviationDataFound(),
                a.getAiSeverity(), a.getAiSummary(), a.getAiSuggestion(),
                a.isHasIssue(), Boolean.TRUE.equals(a.getDismissed()), Boolean.TRUE.equals(a.getIgnoredPermanently()), a.getLastCheckedAt()
        );
    }

    // -----------------------------------------------------------------------
    //  Inner helper class
    // -----------------------------------------------------------------------

    private static class FlightInfo {
        Long airServiceId;
        Long groupId;
        String groupDestination;
        String airline;
        String reservationCode;
        String flightNumber;       // de service_payment_record.flight_number
        LocalDate departureDate;
        String departureTime;
        String departureArrivalTime;
        String origin;
        String destination;
        Long menuItemId;
        String menuItemDisplayName;
        String aviationCacheKey;

        static FlightInfo from(Map<String, Object> row) {
            FlightInfo fi = new FlightInfo();
            fi.airServiceId = toLong(row.get("air_service_id"));
            fi.groupId = toLong(row.get("group_id"));
            fi.groupDestination = toStr(row.get("group_destination"));
            fi.airline = toStr(row.get("airline"));
            fi.reservationCode = toStr(row.get("reservation_code"));
            fi.flightNumber = toStr(row.get("flight_number")); // viene de service_payment_record
            fi.departureTime = toStr(row.get("departure_time"));
            fi.departureArrivalTime = toStr(row.get("departure_arrival_time"));
            fi.origin = toStr(row.get("origin"));
            fi.destination = toStr(row.get("destination"));
            fi.menuItemId = toLong(row.get("menu_item_id"));
            fi.menuItemDisplayName = toStr(row.get("menu_item_display_name"));
            Object dd = row.get("departure_date");
            if (dd instanceof LocalDate ld) fi.departureDate = ld;
            else if (dd instanceof java.sql.Date sd) fi.departureDate = sd.toLocalDate();
            return fi;
        }

        private static Long toLong(Object v) {
            if (v == null) return null;
            if (v instanceof Long l) return l;
            if (v instanceof Number n) return n.longValue();
            return null;
        }

        private static String toStr(Object v) {
            return v == null ? null : v.toString();
        }
    }
}
