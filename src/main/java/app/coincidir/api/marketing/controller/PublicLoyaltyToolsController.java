package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.service.LoyaltyBotToolsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PublicLoyaltyToolsController — Endpoints públicos (sin auth) para que el
 * CoinBot pueda:
 *   - GET  /api/public/loyalty-tools           → listar tools de Marketing
 *   - POST /api/public/loyalty-tools/execute   → ejecutar una tool con args
 *
 * Mismo patrón que PublicBotTableToolsController / PublicBotApiToolsController.
 * El frontend del bot levanta esta lista junto con las otras, las concatena
 * y se las pasa al LLM como tool definitions.
 *
 * Si el módulo Marketing está deshabilitado (bot_config.marketing_enabled=false)
 * el GET devuelve lista vacía y el bot ni se entera de las tools.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/loyalty-tools")
@RequiredArgsConstructor
public class PublicLoyaltyToolsController {

    private final LoyaltyBotToolsService toolsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    @Transactional(readOnly = true)
    public ToolsResponse list() {
        ToolsResponse resp = new ToolsResponse();
        resp.tools = new ArrayList<>();
        resp.meta = new ArrayList<>();

        for (LoyaltyBotToolsService.ToolDef td : toolsService.buildToolsForBot()) {
            ToolEntry t = new ToolEntry();
            t.name = td.name;
            t.description = "[Marketing] " + td.description;
            t.input_schema = td.inputSchema;
            resp.tools.add(t);

            ToolMeta m = new ToolMeta();
            m.name = td.name;
            m.kind = "loyalty";
            // Operaciones de escritura del módulo: enroll, redeem, apply_coupon.
            // Por defecto el bot NO pide confirmación porque son operaciones
            // con costo reversible (canje vencido se revierte solo; enroll
            // duplicado es idempotente; cupón aplicado queda registrado pero
            // el flujo de checkout es del staff, no del bot).
            m.requiresConfirmation = false;
            resp.meta.add(m);
        }
        return resp;
    }

    @PostMapping("/execute")
    @Transactional
    public ExecuteResponse execute(@RequestBody ExecuteRequest req) {
        ExecuteResponse resp = new ExecuteResponse();
        if (req == null || req.toolName == null || req.toolName.isBlank()) {
            resp.ok = false;
            resp.output = "toolName requerido";
            return resp;
        }

        JsonNode args = req.args != null ? req.args : objectMapper.createObjectNode();
        LoyaltyBotToolsService.ToolResult r = toolsService.execute(req.toolName, args);
        resp.ok = r.ok;
        resp.output = r.output;
        return resp;
    }

    // ── DTOs ──────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolsResponse {
        public List<ToolEntry> tools;
        public List<ToolMeta> meta;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolEntry {
        public String name;
        public String description;
        public JsonNode input_schema;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolMeta {
        public String name;
        public String kind;
        public Boolean requiresConfirmation;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteRequest {
        public String toolName;
        public JsonNode args;
        public String sessionId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecuteResponse {
        public Boolean ok;
        public String output;
    }
}
