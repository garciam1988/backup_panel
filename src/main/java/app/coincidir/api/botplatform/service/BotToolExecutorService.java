package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.BotTool;
import app.coincidir.api.botplatform.domain.BotToolAudit;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.BotToolAuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * BotToolExecutorService — Ejecuta tools con parámetros, sobre el conector
 * configurado en la tool.
 *
 * Flujo de una invocación:
 *   1. Validar que la tool esté activa
 *   2. Validar parámetros contra el parameters_schema_json
 *   3. Buscar el conector, obtener su DataSource
 *   4. Crear un NamedParameterJdbcTemplate sobre ese DataSource
 *   5. Ejecutar el SQL (query() para QUERY, update() para UPDATE)
 *   6. Registrar auditoría
 *
 * Seguridad:
 *   • Parámetros pasan por NamedParameterJdbcTemplate → escapado automático
 *     contra SQL injection
 *   • El SQL lo escribe el admin (no Claude), así que no hay inyección via LLM
 *   • Los parámetros enviados por Claude solo son valores, nunca SQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotToolExecutorService {

    private final BotConnectorRepository connectorRepo;
    private final BotToolAuditRepository auditRepo;
    private final DynamicDataSourceService dataSourceService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Ejecuta una tool. Debe llamarse dentro de un @Transactional del caller
     * para que la operación audit esté en un transaction context.
     */
    public ExecutionResult execute(BotTool tool, Map<String, Object> params, String invokedBy) {
        long start = System.currentTimeMillis();
        BotToolAudit audit = new BotToolAudit();
        audit.setToolId(tool.getId());
        audit.setToolName(tool.getName());
        audit.setInvokedBy(invokedBy);
        try {
            audit.setParamsJson(jsonMapper.writeValueAsString(params != null ? params : Map.of()));
        } catch (Exception e) {
            audit.setParamsJson("<serialization failed>");
        }

        try {
            if (!Boolean.TRUE.equals(tool.getActive())) {
                throw new ToolExecutionException("La tool '" + tool.getName() + "' está inactiva");
            }

            // Validar params contra schema (validación básica — solo required fields)
            List<String> missing = validateRequiredParams(tool, params);
            if (!missing.isEmpty()) {
                throw new ToolExecutionException("Faltan parámetros requeridos: " + String.join(", ", missing));
            }

            // Obtener conector
            BotConnector conn = connectorRepo.findById(tool.getConnectorId())
                    .orElseThrow(() -> new ToolExecutionException("Conector no encontrado (id=" + tool.getConnectorId() + ")"));
            if (!Boolean.TRUE.equals(conn.getActive())) {
                throw new ToolExecutionException("El conector '" + conn.getName() + "' está inactivo");
            }

            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
                    dataSourceService.getDataSource(conn));

            MapSqlParameterSource paramSource = new MapSqlParameterSource(
                    params != null ? params : Collections.emptyMap());

            // Ejecutar según tipo
            ExecutionResult result;
            if (tool.getOperationType() == BotTool.OperationType.QUERY) {
                List<Map<String, Object>> rows = jdbc.queryForList(tool.getSqlTemplate(), paramSource);
                int limit = tool.getRowLimit() != null && tool.getRowLimit() > 0 ? tool.getRowLimit() : 100;
                boolean truncated = rows.size() > limit;
                if (truncated) rows = rows.subList(0, limit);
                result = ExecutionResult.query(rows, truncated);
                audit.setRowsAffected(rows.size());
            } else {
                int affected = jdbc.update(tool.getSqlTemplate(), paramSource);
                result = ExecutionResult.update(affected);
                audit.setRowsAffected(affected);
            }

            audit.setSuccess(true);
            audit.setDurationMs(System.currentTimeMillis() - start);
            saveAuditNoThrow(audit);
            return result;

        } catch (ToolExecutionException e) {
            audit.setSuccess(false);
            audit.setErrorMessage(e.getMessage());
            audit.setDurationMs(System.currentTimeMillis() - start);
            saveAuditNoThrow(audit);
            throw e;
        } catch (Exception e) {
            log.error("Error ejecutando tool {}: {}", tool.getName(), e.getMessage(), e);
            audit.setSuccess(false);
            audit.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            audit.setDurationMs(System.currentTimeMillis() - start);
            saveAuditNoThrow(audit);
            throw new ToolExecutionException("Error ejecutando SQL: " + e.getMessage(), e);
        }
    }

    /** Valida que estén presentes los campos 'required' del schema. */
    @SuppressWarnings("unchecked")
    private List<String> validateRequiredParams(BotTool tool, Map<String, Object> params) {
        try {
            if (tool.getParametersSchemaJson() == null || tool.getParametersSchemaJson().isBlank()) {
                return Collections.emptyList();
            }
            Map<String, Object> schema = jsonMapper.readValue(tool.getParametersSchemaJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object required = schema.get("required");
            if (!(required instanceof List)) return Collections.emptyList();

            List<String> missing = new ArrayList<>();
            for (Object fieldObj : (List<?>) required) {
                String field = String.valueOf(fieldObj);
                if (params == null || !params.containsKey(field) || params.get(field) == null) {
                    missing.add(field);
                }
            }
            return missing;
        } catch (Exception e) {
            log.warn("Error validando schema de '{}': {}", tool.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditNoThrow(BotToolAudit audit) {
        try {
            auditRepo.save(audit);
        } catch (Exception e) {
            log.warn("No se pudo guardar auditoría: {}", e.getMessage());
        }
    }

    // ── Tipos de resultado ─────────────────────────────────────────────

    /** Resultado de ejecución. Puede ser query (con rows) o update (con rowsAffected). */
    public static class ExecutionResult {
        public final String type; // "query" | "update"
        public final List<Map<String, Object>> rows;
        public final Integer rowsAffected;
        public final boolean truncated;

        private ExecutionResult(String type, List<Map<String, Object>> rows, Integer ra, boolean trunc) {
            this.type = type; this.rows = rows; this.rowsAffected = ra; this.truncated = trunc;
        }
        public static ExecutionResult query(List<Map<String, Object>> rows, boolean truncated) {
            return new ExecutionResult("query", rows, rows != null ? rows.size() : 0, truncated);
        }
        public static ExecutionResult update(int rowsAffected) {
            return new ExecutionResult("update", null, rowsAffected, false);
        }
    }

    public static class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String msg) { super(msg); }
        public ToolExecutionException(String msg, Throwable cause) { super(msg, cause); }
    }
}
