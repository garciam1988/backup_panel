package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotTool;
import app.coincidir.api.botplatform.domain.BotTool.OperationType;
import app.coincidir.api.botplatform.repository.BotToolRepository;
import app.coincidir.api.botplatform.service.BotToolExecutorService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * BotToolController — CRUD de tools + endpoint de ejecución.
 *
 * Endpoints (requieren JWT):
 *   GET    /api/admin/bot-tools                 → listado (all=true incluye inactivos)
 *   GET    /api/admin/bot-tools/{id}            → uno
 *   POST   /api/admin/bot-tools                 → crear
 *   PUT    /api/admin/bot-tools/{id}            → editar
 *   DELETE /api/admin/bot-tools/{id}            → borrar
 *   POST   /api/admin/bot-tools/{id}/execute    → ejecutar con params {"paramA": ..., "paramB": ...}
 *   GET    /api/admin/bot-tools/for-bot         → lista en formato Anthropic tools
 *   GET    /api/admin/bot-tools/op-types        → lista los tipos de operación
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tools")
@RequiredArgsConstructor
public class BotToolController {

    private final BotToolRepository repo;
    private final BotToolExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping
    @Transactional(readOnly = true)
    public List<BotToolDto> list(@RequestParam(value = "all", defaultValue = "true") boolean all) {
        List<BotTool> list = all
                ? repo.findAllByOrderByNameAsc()
                : repo.findByActiveTrueOrderByNameAsc();
        return list.stream().map(BotToolDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<BotToolDto> getOne(@PathVariable Long id) {
        return repo.findById(id)
                .map(BotToolDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/op-types")
    public List<String> opTypes() {
        return Arrays.stream(OperationType.values()).map(Enum::name).toList();
    }

    /**
     * Tools en formato Anthropic (para que el bot las pase directamente a Claude).
     * Cada tool se serializa como:
     *   { "name": "...", "description": "...", "input_schema": { ... } }
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
                        inputSchema = json.readValue(t.getParametersSchemaJson(),
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        inputSchema = Map.of("type", "object", "properties", Map.of());
                    }
                    tool.put("input_schema", inputSchema);
                    return tool;
                })
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody BotToolDto dto, Authentication auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        String err = dto.validate(json);
        if (err != null) { body.put("error", err); return ResponseEntity.badRequest().body(body); }
        if (repo.findByName(dto.name.trim()).isPresent()) {
            body.put("error", "Ya existe una tool con ese nombre");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        BotTool e = new BotTool();
        dto.applyTo(e);
        if (auth != null) e.setUpdatedBy(auth.getName());
        BotTool saved = repo.save(e);
        log.info("bot_tool creada: id={}, name='{}'", saved.getId(), saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(BotToolDto.fromEntity(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BotToolDto dto, Authentication auth) {
        Optional<BotTool> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String err = dto.validate(json);
        if (err != null) { return ResponseEntity.badRequest().body(Map.of("error", err)); }
        BotTool e = opt.get();
        dto.applyTo(e);
        if (auth != null) e.setUpdatedBy(auth.getName());
        BotTool saved = repo.save(e);
        return ResponseEntity.ok(BotToolDto.fromEntity(saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ejecuta una tool. El body es un JSON con los parámetros:
     *   { "nombre": "pizza", "precio_max": 10000 }
     */
    @PostMapping("/{id}/execute")
    @Transactional
    public ResponseEntity<Map<String, Object>> execute(@PathVariable Long id,
                                                        @RequestBody(required = false) Map<String, Object> params,
                                                        Authentication auth) {
        Optional<BotTool> opt = repo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "error", "Tool no encontrada"));
        }
        String invokedBy = auth != null ? auth.getName() : "unknown";
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            BotToolExecutorService.ExecutionResult result = executor.execute(opt.get(), params, invokedBy);
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
            return ResponseEntity.ok(body); // Devolvemos 200 con ok=false para que el bot lo pueda leer
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotToolDto {
        public Long    id;
        public String  name;
        public String  description;
        public String  parametersSchemaJson;
        public String  sqlTemplate;
        public String  operationType;
        public Long    connectorId;
        public Integer rowLimit;
        public Boolean active;
        public Instant createdAt;
        public Instant updatedAt;
        public String  updatedBy;

        public static BotToolDto fromEntity(BotTool e) {
            BotToolDto d = new BotToolDto();
            d.id                   = e.getId();
            d.name                 = e.getName();
            d.description          = e.getDescription();
            d.parametersSchemaJson = e.getParametersSchemaJson();
            d.sqlTemplate          = e.getSqlTemplate();
            d.operationType        = e.getOperationType() != null ? e.getOperationType().name() : null;
            d.connectorId          = e.getConnectorId();
            d.rowLimit             = e.getRowLimit();
            d.active               = e.getActive();
            d.createdAt            = e.getCreatedAt();
            d.updatedAt            = e.getUpdatedAt();
            d.updatedBy            = e.getUpdatedBy();
            return d;
        }

        public void applyTo(BotTool e) {
            if (name != null)                 e.setName(name.trim());
            if (description != null)          e.setDescription(description);
            if (parametersSchemaJson != null) e.setParametersSchemaJson(parametersSchemaJson);
            if (sqlTemplate != null)          e.setSqlTemplate(sqlTemplate);
            if (operationType != null)        e.setOperationType(OperationType.valueOf(operationType.toUpperCase()));
            if (connectorId != null)          e.setConnectorId(connectorId);
            if (rowLimit != null)             e.setRowLimit(rowLimit);
            if (active != null)               e.setActive(active);
        }

        public String validate(ObjectMapper json) {
            if (name == null || name.isBlank()) return "name es obligatorio";
            if (!name.matches("[a-z][a-z0-9_]{1,99}")) {
                return "name debe ser snake_case (lowercase, empezar con letra, solo letras/dígitos/_). Ej: 'buscar_producto'";
            }
            if (description == null || description.isBlank()) return "description es obligatoria";
            if (sqlTemplate == null || sqlTemplate.isBlank()) return "sqlTemplate es obligatorio";
            if (parametersSchemaJson == null || parametersSchemaJson.isBlank()) {
                parametersSchemaJson = "{\"type\":\"object\",\"properties\":{}}";
            } else {
                // Validar que sea JSON parseable
                try { json.readTree(parametersSchemaJson); }
                catch (Exception ex) { return "parametersSchemaJson no es JSON válido: " + ex.getMessage(); }
            }
            if (operationType == null || operationType.isBlank()) return "operationType es obligatorio (QUERY o UPDATE)";
            try { OperationType.valueOf(operationType.toUpperCase()); }
            catch (Exception ex) { return "operationType inválido: " + operationType; }
            if (connectorId == null) return "connectorId es obligatorio";
            return null;
        }
    }
}
