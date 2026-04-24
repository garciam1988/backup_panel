package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ApiEndpoint;
import app.coincidir.api.botplatform.domain.ApiIntegration;
import app.coincidir.api.botplatform.repository.ApiEndpointRepository;
import app.coincidir.api.botplatform.repository.ApiIntegrationRepository;
import app.coincidir.api.botplatform.service.ApiCallExecutor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PublicBotApiToolsController — expone al CoinBot las tools de APIs externas
 * que el admin activó. Sin auth (path /api/public/**) porque el bot corre en
 * el chat del cliente y no tiene JWT.
 *
 * Endpoints:
 *   GET  /api/public/bot-api-tools            — lista tools activas (para meter en system/tools de Claude)
 *   POST /api/public/bot-api-tools/execute    — ejecuta una tool por toolName con args
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-api-tools")
@RequiredArgsConstructor
public class PublicBotApiToolsController {

    private final ApiEndpointRepository endpointRepo;
    private final ApiIntegrationRepository integrationRepo;
    private final ApiCallExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Lista las tools activas en formato compatible con el tool-calling de Claude.
     * Cada entrada incluye name, description e input_schema (JSON Schema).
     * El bot las concatena con sus tools locales antes de llamar a /v1/messages.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ToolsListResponse list() {
        ToolsListResponse resp = new ToolsListResponse();
        resp.tools = new ArrayList<>();
        resp.meta = new ArrayList<>();

        List<ApiEndpoint> active = endpointRepo.findByActiveAsToolTrue();
        // Cache de integrations consultadas para no pegarle a la BD por cada endpoint
        Map<Long, ApiIntegration> cache = new HashMap<>();

        for (ApiEndpoint ep : active) {
            ApiIntegration integ = cache.computeIfAbsent(ep.getIntegrationId(),
                    id -> integrationRepo.findById(id).orElse(null));
            if (integ == null || !Boolean.TRUE.equals(integ.getActive())) continue;

            ToolEntry t = new ToolEntry();
            t.name = ep.getToolName();
            // Prefijo descriptivo para ayudar a Claude a entender el alcance
            String desc = Optional.ofNullable(ep.getDescription()).orElse("");
            t.description = "[API: " + integ.getName() + "] " + desc;
            if (!ep.isReadOnly() && Boolean.TRUE.equals(ep.getAllowWrites())) {
                t.description += " (ATENCIÓN: esta acción MODIFICA datos — confirmá con el usuario antes de invocarla)";
            }

            // Parsear el inputSchemaJson; si es null, usamos un schema vacío
            JsonNode schema;
            try {
                schema = ep.getInputSchemaJson() != null && !ep.getInputSchemaJson().isBlank()
                        ? objectMapper.readTree(ep.getInputSchemaJson())
                        : objectMapper.readTree("{\"type\":\"object\",\"properties\":{}}");
            } catch (Exception e) {
                log.warn("[Tools] schema inválido para {}: {}", t.name, e.getMessage());
                try { schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{}}"); }
                catch (Exception ex) { continue; }
            }
            t.input_schema = schema;
            resp.tools.add(t);

            // Metadata paralelo: flags que el bot necesita para decidir si pedir confirmación
            ToolMeta m = new ToolMeta();
            m.name = t.name;
            m.integrationName = integ.getName();
            m.method = ep.getMethod();
            m.path = ep.getPath();
            m.requireConfirmation = Boolean.TRUE.equals(ep.getRequireConfirmation());
            m.isWrite = !ep.isReadOnly();
            resp.meta.add(m);
        }

        return resp;
    }

    /**
     * Ejecuta una tool de API por toolName. Body esperado:
     *   { "toolName": "search_products", "args": { ... } }
     *
     * La respuesta siempre tiene 200 HTTP status — los errores se devuelven
     * dentro de `output` con texto descriptivo para que Claude pueda razonar.
     */
    @PostMapping("/execute")
    @Transactional(readOnly = true)
    public ExecuteResponse execute(@RequestBody ExecuteRequest req) {
        ExecuteResponse resp = new ExecuteResponse();
        if (req == null || req.toolName == null || req.toolName.isBlank()) {
            resp.ok = false;
            resp.output = "Falta 'toolName' en el request.";
            return resp;
        }

        Optional<ApiEndpoint> found = endpointRepo.findByToolName(req.toolName);
        if (found.isEmpty()) {
            resp.ok = false;
            resp.output = "No existe ninguna tool con nombre '" + req.toolName + "'.";
            return resp;
        }

        ApiEndpoint ep = found.get();
        if (!Boolean.TRUE.equals(ep.getActiveAsTool())) {
            resp.ok = false;
            resp.output = "La tool '" + req.toolName + "' está deshabilitada.";
            return resp;
        }

        JsonNode argsNode = req.args != null ? req.args : objectMapper.createObjectNode();
        ApiCallExecutor.ExecutionResult r = executor.execute(ep.getId(), argsNode);
        ApiIntegration integ = integrationRepo.findById(ep.getIntegrationId()).orElse(null);
        String integName = integ != null ? integ.getName() : null;
        resp.ok = r.ok;
        resp.httpStatus = r.httpStatus;
        resp.durationMs = r.durationMs;
        resp.output = executor.formatForClaude(r, ep.getToolName(), integName);
        return resp;
    }

    // ─────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolsListResponse {
        public List<ToolEntry> tools;
        public List<ToolMeta> meta;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolEntry {
        public String name;
        public String description;
        public JsonNode input_schema;
    }

    /** Metadata paralela que el frontend usa para decidir si pedir confirmación. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolMeta {
        public String name;
        public String integrationName;
        public String method;
        public String path;
        public Boolean requireConfirmation;
        public Boolean isWrite;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteRequest {
        public String toolName;
        public JsonNode args;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteResponse {
        public Boolean ok;
        public Integer httpStatus;
        public Long durationMs;
        public String output;
    }
}
