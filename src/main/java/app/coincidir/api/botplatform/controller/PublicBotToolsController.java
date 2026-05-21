package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTool;
import app.coincidir.api.botplatform.repository.BotToolRepository;
import app.coincidir.api.botplatform.service.BotToolExecutorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * PublicBotToolsController — expone al CoinBot público (cliente final, sin JWT)
 * las BotTool tradicionales (sistema SQL-template del Fase 2 inicial).
 *
 * Mismo patrón que ya usamos en:
 *   - PublicBotApiToolsController   (tools de APIs externas)
 *   - PublicBotTableToolsController (tools de BotTables internas)
 *   - PublicSqlExecController       (SQL libre con schema embebido)
 *   - PublicLoyaltyToolsController  (tools nativas del módulo Marketing)
 *
 * Endpoints:
 *   GET  /api/public/bot-tools/for-bot     → lista en formato Anthropic tools
 *                                            (name, description, input_schema)
 *                                            sin exponer id ni sql_template.
 *   POST /api/public/bot-tools/execute     → ejecuta una tool por NOMBRE
 *                                            (no por id, para no exponer ids
 *                                            internos al cliente público).
 *                                            Body: { "name": "...", "params": {...} }
 *
 * Por qué público (sin JWT):
 *   El CoinBot del cliente final corre en su browser sin token. La protección
 *   está en:
 *     - Las tools las define el admin (el SQL template NO viene del cliente
 *       ni de Claude — está hardcodeado en la BD).
 *     - Los parámetros van por NamedParameterJdbcTemplate → sin SQL injection.
 *     - Solo se exponen tools con active=true.
 *     - Devolvemos 200 con {ok:false} en errores para que el bot los maneje
 *       sin que el cliente vea stack traces.
 *
 * Si en el futuro se quiere restringir QUÉ tools son públicas (vs solo
 * accesibles desde el admin), agregar un flag `public_exposed` en BotTool
 * y filtrar acá. Por ahora todas las activas se exponen — es coherente con
 * lo que ya hacen los otros endpoints Public*.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-tools")
@RequiredArgsConstructor
public class PublicBotToolsController {

    private final BotToolRepository repo;
    private final BotToolExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Devuelve las tools activas en formato compatible con el tool-calling de
     * Claude. Cada entrada incluye name, description e input_schema.
     *
     * Notar que NO exponemos el id ni el sql_template. El frontend ejecuta
     * por nombre (POST /execute con {name, params}), y el SQL nunca sale del
     * backend.
     */
    @GetMapping("/for-bot")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forBot() {
        return repo.findByActiveTrueOrderByNameAsc().stream()
                .map(t -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", t.getName());
                    tool.put("description", t.getDescription());
                    Object inputSchema;
                    try {
                        inputSchema = json.readValue(
                                t.getParametersSchemaJson(),
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("[PublicBotTools] schema inválido para '{}': {}",
                                t.getName(), e.getMessage());
                        inputSchema = Map.of("type", "object", "properties", Map.of());
                    }
                    tool.put("input_schema", inputSchema);
                    return tool;
                })
                .toList();
    }

    /**
     * Ejecuta una tool por nombre. Body esperado:
     *   { "name": "buscar_pasajero_por_operacion", "params": { "operation_id": 1117 } }
     *
     * Respuesta (mismo shape que /api/admin/bot-tools/{id}/execute):
     *   { ok: true,  type: "query",  rows: [...], rowCount: N, truncated: bool }
     *   { ok: true,  type: "update", rowsAffected: N }
     *   { ok: false, error: "..." }                       (status 200, para que el bot lo lea)
     *   { ok: false, error: "Tool no encontrada" }        (status 404)
     */
    @PostMapping("/execute")
    @Transactional
    public ResponseEntity<Map<String, Object>> execute(@RequestBody ExecuteRequest req,
                                                       HttpServletRequest httpReq) {
        Map<String, Object> body = new LinkedHashMap<>();

        if (req == null || req.name == null || req.name.isBlank()) {
            body.put("ok", false);
            body.put("error", "Falta 'name' de la tool a ejecutar");
            return ResponseEntity.badRequest().body(body);
        }

        Optional<BotTool> opt = repo.findByName(req.name.trim());
        if (opt.isEmpty()) {
            body.put("ok", false);
            body.put("error", "Tool no encontrada: " + req.name);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        BotTool tool = opt.get();
        // Defensa en profundidad: aunque el /for-bot solo lista activas, no
        // confiamos en eso porque el cliente puede mandar cualquier name.
        if (!Boolean.TRUE.equals(tool.getActive())) {
            body.put("ok", false);
            body.put("error", "Tool inactiva: " + req.name);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        // Marcador de quién invocó — útil para distinguir invocaciones de cliente
        // final vs admin en bot_tool_audit. Incluimos IP para tener algo trazable
        // sin exponer datos personales.
        String invokedBy = "public-bot:" + clientIp(httpReq);

        try {
            BotToolExecutorService.ExecutionResult result =
                    executor.execute(tool, req.params, invokedBy);
            body.put("ok", true);
            body.put("type", result.type);
            if (result.rows != null) {
                body.put("rows", result.rows);
                body.put("rowCount", result.rows.size());
                body.put("truncated", result.truncated);
            } else {
                body.put("rowsAffected", result.rowsAffected);
            }
            return ResponseEntity.ok(body);
        } catch (BotToolExecutorService.ToolExecutionException e) {
            body.put("ok", false);
            body.put("error", e.getMessage());
            // 200 a propósito: queremos que el frontend lea el {ok:false, error}
            // como si fuera un tool_result normal y se lo pase a Claude para
            // que decida cómo seguir (mismo criterio que el endpoint admin).
            return ResponseEntity.ok(body);
        }
    }

    /** Obtiene la IP real del cliente, considerando X-Forwarded-For (Railway/proxy). */
    private static String clientIp(HttpServletRequest req) {
        if (req == null) return "unknown";
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /** Body del POST /execute. */
    public static class ExecuteRequest {
        public String name;
        public Map<String, Object> params;
    }
}
