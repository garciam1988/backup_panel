package app.coincidir.api.web.admin.ai;

import app.coincidir.api.domain.*;
import app.coincidir.api.repository.AiLearnedRuleRepository;
import app.coincidir.api.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Helper transaccional separado para que @Transactional funcione correctamente
 * cuando es llamado desde el contexto @Async de AiSuggestionsService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiGroupAnalyzerTx {

    private final TravelGroupRepository groupRepo;
    private final TravelRequestRepository requestRepo;
    private final GroupServiceMenuItemRepository menuItemRepo;
    private final GroupFerryServiceRepository ferryRepo;
    private final GroupAirServiceRepository airRepo;
    private final GroupAccommodationServiceRepository accommodationRepo;
    private final GroupTransferServiceRepository transferRepo;
    private final GroupDestinationTransferServiceRepository destTransferRepo;
    private final AiOperationSuggestionRepository suggestionRepo;
    private final AiLearnedRuleRepository learnedRuleRepo;
    private final ObjectMapper objectMapper;

    private static final Set<GroupStatus> ACTIVE_STATUSES = Set.of(
            GroupStatus.EN_COTIZACION,
            GroupStatus.EN_CONCILIACION,
            GroupStatus.CONCILIADO,
            GroupStatus.EN_OPERACIONES_SC,
            GroupStatus.EN_OPERACIONES,
            GroupStatus.PENDIENTE_CONCILIACION,
            GroupStatus.CLOSED
    );

    @Transactional(readOnly = true)
    public List<TravelGroup> loadActiveGroups() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return groupRepo.findAll().stream()
                .filter(g -> ACTIVE_STATUSES.contains(g.getStatus()))
                // Excluir operaciones cuyo viaje ya concluyó
                .filter(g -> {
                    java.time.LocalDate endDate = g.getTravelEndDate();
                    if (endDate != null) return !endDate.isBefore(today);
                    // Sin fecha de fin: usar fecha de inicio si existe
                    java.time.LocalDate startDate = g.getTravelStartDate();
                    if (startDate != null) return !startDate.isBefore(today.minusDays(1));
                    return true; // Sin fechas: incluir por precaución
                })
                .collect(Collectors.toList());
    }

    /** Construye el contexto JSON de una operación. Devuelve null si no tiene servicios. */
    @Transactional(readOnly = true)
    public String buildContext(Long groupId) {
        TravelGroup group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return null;

        List<GroupServiceMenuItem> items = menuItemRepo.findByGroupIdOrderByPositionAsc(groupId);
        if (items.isEmpty()) return null;

        long memberCount = requestRepo.countByGroupId(groupId);

        ObjectNode ctx = objectMapper.createObjectNode();
        ctx.put("operacion_id", group.getId());
        ctx.put("destino", group.getDestination() != null ? group.getDestination() : "No especificado");
        ctx.put("estado", group.getStatus().name());
        ctx.put("fecha_inicio", group.getTravelStartDate() != null ? group.getTravelStartDate().toString() : "No definida");
        ctx.put("fecha_fin", group.getTravelEndDate() != null ? group.getTravelEndDate().toString() : "No definida");
        ctx.put("cantidad_pasajeros", memberCount);

        ArrayNode servicios = objectMapper.createArrayNode();
        for (GroupServiceMenuItem item : items) {
            ObjectNode svc = objectMapper.createObjectNode();
            String code = item.getService() != null ? item.getService().getCode().name() : "DESCONOCIDO";
            svc.put("tipo", code);
            svc.put("nombre", item.getDisplayName());

            switch (code) {
                case "FERRY" -> ferryRepo.findByMenuItemId(item.getId()).ifPresent(f -> {
                    svc.put("origen", f.getOriginPort());
                    svc.put("destino", f.getDestinationPort());
                    svc.put("fecha_ida", f.getDepartureDate() != null ? f.getDepartureDate().toString() : null);
                    svc.put("hora_salida_ida", f.getDepartureTime() != null ? f.getDepartureTime().toString() : null);
                    svc.put("hora_llegada_ida", f.getDepartureArrivalTime() != null ? f.getDepartureArrivalTime().toString() : null);
                    svc.put("fecha_vuelta", f.getReturnDate() != null ? f.getReturnDate().toString() : null);
                    svc.put("hora_salida_vuelta", f.getReturnTime() != null ? f.getReturnTime().toString() : null);
                    svc.put("hora_llegada_vuelta", f.getReturnArrivalTime() != null ? f.getReturnArrivalTime().toString() : null);
                    svc.put("empresa", f.getFerryCompany());
                    svc.put("tipo_viaje", f.getTripType() != null ? f.getTripType().name() : null);
                });
                case "AEREOS" -> airRepo.findByMenuItemId(item.getId()).ifPresent(a -> {
                    svc.put("origen", a.getOrigin());
                    svc.put("destino", a.getDestination());
                    svc.put("aerolinea", a.getAirline());
                    svc.put("fecha_salida", a.getDepartureDate() != null ? a.getDepartureDate().toString() : null);
                    svc.put("hora_salida", a.getDepartureTime() != null ? a.getDepartureTime().toString() : null);
                    svc.put("hora_llegada", a.getDepartureArrivalTime() != null ? a.getDepartureArrivalTime().toString() : null);
                    svc.put("fecha_vuelta", a.getReturnDate() != null ? a.getReturnDate().toString() : null);
                    svc.put("hora_salida_vuelta", a.getReturnDepartureTime() != null ? a.getReturnDepartureTime().toString() : null);
                    svc.put("hora_llegada_vuelta", a.getReturnArrivalTime() != null ? a.getReturnArrivalTime().toString() : null);
                    svc.put("tipo_viaje", a.getTripType());
                });
                case "ALOJAMIENTOS" -> accommodationRepo.findByMenuItemId(item.getId()).ifPresent(acc -> {
                    svc.put("nombre_hotel", acc.getName());
                    svc.put("ciudad", acc.getCity());
                    svc.put("pais", acc.getCountry());
                    svc.put("fecha_checkin", acc.getCheckInDate() != null ? acc.getCheckInDate().toString() : null);
                    svc.put("hora_checkin", acc.getCheckInTime() != null ? acc.getCheckInTime().toString() : null);
                    svc.put("fecha_checkout", acc.getCheckOutDate() != null ? acc.getCheckOutDate().toString() : null);
                    svc.put("hora_checkout", acc.getCheckOutTime() != null ? acc.getCheckOutTime().toString() : null);
                    svc.put("regimen", acc.getRegimen() != null ? acc.getRegimen().name() : null);
                });
                case "TRASLADOS" -> transferRepo.findByMenuItemId(item.getId()).ifPresent(t -> {
                    svc.put("desde", t.getPickupPlace());
                    svc.put("hasta", t.getDestinationPlace());
                    svc.put("fecha_ida", t.getDepartureDate() != null ? t.getDepartureDate().toString() : null);
                    svc.put("hora_ida", t.getDepartureTime() != null ? t.getDepartureTime().toString() : null);
                    svc.put("fecha_vuelta", t.getReturnDate() != null ? t.getReturnDate().toString() : null);
                    svc.put("hora_vuelta", t.getReturnTime() != null ? t.getReturnTime().toString() : null);
                });
                case "TRASLADOS_DESTINO" -> destTransferRepo.findByMenuItemId(item.getId()).ifPresent(dt -> {
                    svc.put("desde", dt.getPickupPlace());
                    svc.put("hasta", dt.getDestinationPlace());
                    svc.put("fecha_ida", dt.getDepartureDate() != null ? dt.getDepartureDate().toString() : null);
                    svc.put("hora_ida", dt.getDepartureTime() != null ? dt.getDepartureTime().toString() : null);
                });
            }
            servicios.add(svc);
        }
        ctx.set("servicios", servicios);

        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void saveResult(Long groupId, String claudeText, ObjectMapper om) {
        TravelGroup group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return;

        try {
            String clean = claudeText.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode result = om.readTree(clean);
            String severity = result.path("severity").asText("WARNING");
            String summary = result.path("summary").asText("Análisis completado");
            String findingsJson = om.writeValueAsString(result.path("findings"));

            AiOperationSuggestion sugg = suggestionRepo.findByGroupId(groupId)
                    .orElseGet(AiOperationSuggestion::new);

            sugg.setGroupId(group.getId());
            sugg.setGroupDestination(group.getDestination());
            sugg.setTravelStartDate(group.getTravelStartDate());
            sugg.setTravelEndDate(group.getTravelEndDate());

            // Filtrar findings suprimidos para esta OP específica
            String filteredFindingsJson = findingsJson;
            String suppressed = sugg.getSuppressedFindingsJson();
            if (suppressed != null && !suppressed.isBlank()) {
                try {
                    List<String> suppressedTitles = new ArrayList<>();
                    com.fasterxml.jackson.databind.JsonNode suppArr = om.readTree(suppressed);
                    if (suppArr.isArray()) for (com.fasterxml.jackson.databind.JsonNode n : suppArr) suppressedTitles.add(n.asText().toLowerCase().trim());

                    com.fasterxml.jackson.databind.JsonNode findingsArr = om.readTree(findingsJson);
                    com.fasterxml.jackson.databind.node.ArrayNode filtered = om.createArrayNode();
                    if (findingsArr.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode f : findingsArr) {
                            String title = f.path("title").asText("").toLowerCase().trim();
                            boolean isSuppressed = suppressedTitles.stream().anyMatch(s -> title.contains(s) || s.contains(title));
                            if (!isSuppressed) filtered.add(f);
                        }
                    }
                    filteredFindingsJson = om.writeValueAsString(filtered);
                    // Recalcular severity si quedan 0 findings
                    if (filtered.isEmpty()) { severity = "OK"; summary = "Sin inconsistencias activas"; }
                } catch (Exception ex) {
                    log.warn("[AiSuggestions] Error filtrando suprimidos: {}", ex.getMessage());
                }
            }

            sugg.setSummary(summary);
            sugg.setFindingsJson(filteredFindingsJson);
            sugg.setSeverity(severity);
            sugg.setLastRunAt(Instant.now());
            if ("OK".equals(severity)) {
                sugg.setDismissed(true); // auto-ocultar cuando ya no hay errores
            } else {
                sugg.setDismissed(false); // re-mostrar si volvió a tener error
            }

            suggestionRepo.save(sugg);
            log.debug("[AiSuggestions] Guardado resultado grupo {} severity={}", groupId, severity);

        } catch (Exception e) {
            log.warn("[AiSuggestions] Error parseando respuesta Claude para grupo {}: {}", groupId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AiSuggestionDto> listActive(ObjectMapper om) {
        return suggestionRepo.findByDismissedFalseOrderByLastRunAtDesc().stream()
                .map(s -> toDto(s, om))
                .collect(Collectors.toList());
    }

    @Transactional
    public void dismiss(Long id) {
        suggestionRepo.findById(id).ifPresent(s -> {
            s.setDismissed(true);
            suggestionRepo.save(s);
        });
    }

    @Transactional(readOnly = true)
    public String loadSuppressedFindings(Long groupId) {
        return suggestionRepo.findByGroupId(groupId)
                .map(s -> s.getSuppressedFindingsJson())
                .orElse(null);
    }

    @Transactional
    public void suppressFinding(Long suggestionId, String findingTitle) {
        suggestionRepo.findById(suggestionId).ifPresent(s -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();

                // 1. Agregar título a la lista de suprimidos
                List<String> suppressed = new ArrayList<>();
                String existing = s.getSuppressedFindingsJson();
                if (existing != null && !existing.isBlank()) {
                    com.fasterxml.jackson.databind.JsonNode arr = om.readTree(existing);
                    if (arr.isArray()) for (com.fasterxml.jackson.databind.JsonNode n : arr) suppressed.add(n.asText());
                }
                if (!suppressed.contains(findingTitle)) suppressed.add(findingTitle);
                s.setSuppressedFindingsJson(om.writeValueAsString(suppressed));

                // 2. Quitar el finding de findings_json INMEDIATAMENTE
                String findingsJson = s.getFindingsJson();
                if (findingsJson != null && !findingsJson.isBlank()) {
                    com.fasterxml.jackson.databind.JsonNode arr = om.readTree(findingsJson);
                    com.fasterxml.jackson.databind.node.ArrayNode filtered = om.createArrayNode();
                    if (arr.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode f : arr) {
                            String title = f.path("title").asText("").toLowerCase().trim();
                            String suppTitle = findingTitle.toLowerCase().trim();
                            boolean match = title.equals(suppTitle)
                                    || title.contains(suppTitle)
                                    || suppTitle.contains(title);
                            if (!match) filtered.add(f);
                        }
                    }
                    s.setFindingsJson(om.writeValueAsString(filtered));
                    // 3. Recalcular severity si quedan 0 findings
                    if (filtered.isEmpty()) {
                        s.setSeverity("OK");
                        s.setSummary("Sin inconsistencias activas");
                    }
                }

                suggestionRepo.save(s);
                log.info("[AiSuggestions] Finding suprimido y findings_json actualizado para suggestion {}", suggestionId);
            } catch (Exception e) {
                log.warn("[AiSuggestions] Error suprimiendo finding: {}", e.getMessage());
            }
        });
    }

    @Transactional
    public void saveLearnedRule(String findingTitle, String findingDescription, String userReason) {
        AiLearnedRule rule = new AiLearnedRule();
        rule.setFindingTitle(findingTitle);
        rule.setFindingDescription(findingDescription);
        rule.setUserReason(userReason);
        learnedRuleRepo.save(rule);
    }

    @Transactional(readOnly = true)
    public String buildLearnedRulesContext() {
        var rules = learnedRuleRepo.findAllByOrderByCreatedAtAsc();
        if (rules.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("Las siguientes situaciones fueron revisadas por el operador y CONFIRMADAS COMO CORRECTAS.\n");
        sb.append("NO reportar ningun finding similar a estos. La razon del operador define el umbral correcto.\n\n");
        for (var r : rules) {
            sb.append("SITUACION VALIDADA: \"").append(r.getFindingTitle()).append("\"\n");
            if (r.getUserReason() != null && !r.getUserReason().isBlank()) {
                sb.append("REGLA DEL OPERADOR: ").append(r.getUserReason()).append("\n");
            }
            sb.append("→ NO REPORTAR situaciones de este tipo salvo que violen la regla indicada arriba.\n\n");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public AiStatusDto getStatus(boolean running, ObjectMapper om) {
        var all = suggestionRepo.findByDismissedFalseOrderByLastRunAtDesc();
        int errors = (int) all.stream().filter(s -> "ERROR".equals(s.getSeverity())).count();
        int warnings = (int) all.stream().filter(s -> "WARNING".equals(s.getSeverity())).count();
        int ok = (int) all.stream().filter(s -> "OK".equals(s.getSeverity())).count();
        var lastRun = all.stream().map(s -> s.getLastRunAt()).filter(t -> t != null)
                .max(java.util.Comparator.naturalOrder()).orElse(null);
        return new AiStatusDto(running, errors, warnings, ok, all.size(), lastRun);
    }

    private AiSuggestionDto toDto(AiOperationSuggestion s, ObjectMapper om) {
        List<AiSuggestionDto.FindingDto> findings = new ArrayList<>();
        try {
            JsonNode arr = om.readTree(s.getFindingsJson() != null ? s.getFindingsJson() : "[]");
            if (arr.isArray()) {
                for (JsonNode f : arr) {
                    findings.add(new AiSuggestionDto.FindingDto(
                            f.path("type").asText("INFO"),
                            f.path("title").asText(""),
                            f.path("description").asText(""),
                            f.path("suggestion").asText("")
                    ));
                }
            }
        } catch (Exception ignored) {}

        return new AiSuggestionDto(
                s.getId(), s.getGroupId(), s.getGroupDestination(),
                s.getTravelStartDate(), s.getTravelEndDate(),
                s.getSummary(), s.getSeverity(), s.getLastRunAt(), findings
        );
    }
}
