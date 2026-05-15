package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.repository.ConnectorSchemaCacheRepository;
import app.coincidir.api.botplatform.service.SqlExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PublicSqlExecController — endpoints públicos para el CoinBot (frontend del
 * bot que corre en el browser del cliente final).
 *
 *   GET  /api/public/sql-exec/info     → lista de conectores con SQL libre habilitado
 *   POST /api/public/sql-exec/execute  → ejecuta un SQL con todas las salvaguardas
 *
 * Por qué público (sin JWT):
 *   - El CoinBot del cliente final no tiene JWT; navega anónimo.
 *   - La protección está en SqlSafetyValidator + límites del conector + flag
 *     sqlExecEnabled. Solo conectores explícitamente habilitados aparecen.
 *   - Lo mismo que ya hacemos con /api/public/bot-api-tools y bot-table-tools.
 *
 * El sql_exec_enabled del conector funciona como switch maestro: si el admin
 * lo deja en false (default), este endpoint no devuelve nada y la tool no se
 * activa en el bot. Es opt-in explícito por seguridad.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/sql-exec")
@RequiredArgsConstructor
public class PublicSqlExecController {

    private final BotConnectorRepository connectorRepo;
    private final ConnectorSchemaCacheRepository schemaRepo;
    private final SqlExecutionService sqlExec;

    /**
     * Lista los conectores que el bot puede consultar con SQL libre.
     * Para cada uno devuelve la descripción y el schema en formato resumido
     * para que el frontend lo inyecte al system prompt y construya la tool.
     *
     * Si el conector tiene whitelist de tablas configurada, el llmSummary
     * que devolvemos solo incluye esas tablas. Claude no se entera de las
     * otras (defensa en profundidad: aunque Claude se inventara un nombre,
     * el guardrail al ejecutar también lo bloquea).
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        List<BotConnector> connectors = connectorRepo.findAll();

        List<Map<String, Object>> enabled = new ArrayList<>();
        for (BotConnector c : connectors) {
            if (!Boolean.TRUE.equals(c.getActive())) continue;
            if (!c.isSqlExecEnabled()) continue;

            // Necesitamos el schema cacheado — sin él Claude no sabe qué consultar.
            ConnectorSchemaCache cache = schemaRepo.findByConnectorId(c.getId()).orElse(null);
            if (cache == null || cache.getLlmSummary() == null || cache.getLlmSummary().isBlank()) continue;

            // Aplicar whitelist al schemaSummary que sirve al bot
            Set<String> whitelist = c.getTableWhitelist();
            String filteredSummary = whitelist.isEmpty()
                    ? cache.getLlmSummary()
                    : filterSummaryByWhitelist(cache.getLlmSummary(), whitelist);

            // Si después de filtrar no queda nada útil (la whitelist no
            // matchea ninguna tabla real), skipeamos el conector. Mejor
            // que mostrarle a Claude una tool sin contexto.
            int tablesInSummary = countTablesInSummary(filteredSummary);
            if (tablesInSummary == 0) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("connectorId", c.getId());
            entry.put("name", c.getName());
            entry.put("description", c.getDescription());
            entry.put("dbType", c.getDbType().name());
            entry.put("databaseName", c.getDatabaseName());
            entry.put("schemaSummary", filteredSummary);
            // Si la whitelist filtró, mostramos el conteo POST-filtro como
            // tableCount para que el cliente sepa cuántas tablas ve el bot.
            entry.put("tableCount", whitelist.isEmpty() ? cache.getTableCount() : tablesInSummary);
            entry.put("columnCount", cache.getColumnCount()); // aprox, no recalculamos
            entry.put("maxRows", c.getMaxRows());
            entry.put("timeoutSec", c.getTimeoutSec());

            // toolName slug-ificado y único: query_<id>_<nombre>
            entry.put("toolName", buildToolName(c));
            enabled.add(entry);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("connectors", enabled);
        return resp;
    }

    /**
     * El llmSummary viene del SchemaIntrospectionService.buildLlmSummary
     * con formato:
     *
     *   -- Base: MYSQL (N tablas, M columnas)
     *
     *   TABLE reservas (
     *     id INT PK,
     *     ...
     *   )
     *
     *   TABLE clientes (
     *     ...
     *   )
     *
     * Para filtrar, parseamos por bloques que empiezan con "TABLE <nombre> ("
     * y conservamos solo los que matchean la whitelist (case-insensitive).
     */
    private String filterSummaryByWhitelist(String llmSummary, Set<String> whitelist) {
        if (llmSummary == null || llmSummary.isBlank()) return llmSummary;

        StringBuilder out = new StringBuilder();
        String[] lines = llmSummary.split("\\R", -1);

        boolean keepingBlock = false;
        boolean firstBlockKept = false;
        StringBuilder currentBlock = null;
        String currentTable = null;

        for (String line : lines) {
            // Header de tabla: "TABLE nombre ("
            if (line.startsWith("TABLE ")) {
                // Flush del bloque anterior si correspondía
                if (currentBlock != null && keepingBlock) {
                    if (firstBlockKept) out.append("\n");
                    out.append(currentBlock);
                    firstBlockKept = true;
                }
                currentBlock = new StringBuilder();
                // Parsear nombre: "TABLE foo (" → "foo"
                int spaceIdx = line.indexOf(' ');
                int parenIdx = line.indexOf('(');
                currentTable = (spaceIdx >= 0 && parenIdx > spaceIdx)
                        ? line.substring(spaceIdx + 1, parenIdx).trim().toLowerCase(java.util.Locale.ROOT)
                        : "";
                keepingBlock = whitelist.contains(currentTable);
                if (keepingBlock) currentBlock.append(line).append("\n");
                continue;
            }
            // Línea de header global ("-- Base: ...") → la conservamos siempre
            if (currentBlock == null) {
                out.append(line).append("\n");
                continue;
            }
            if (keepingBlock) {
                currentBlock.append(line).append("\n");
            }
        }
        // Flush del último bloque
        if (currentBlock != null && keepingBlock) {
            if (firstBlockKept) out.append("\n");
            out.append(currentBlock);
        }
        return out.toString();
    }

    private int countTablesInSummary(String summary) {
        if (summary == null) return 0;
        int n = 0;
        for (String line : summary.split("\\R", -1)) {
            if (line.startsWith("TABLE ")) n++;
        }
        return n;
    }

    public record ExecRequest(Long connectorId, String sql, String sessionId, String userQuestion) {}

    @PostMapping("/execute")
    public SqlExecutionService.ExecResult execute(@RequestBody ExecRequest body) {
        if (body == null || body.connectorId() == null) {
            return new SqlExecutionService.ExecResult(
                    false, "VALIDATION_FAILED", "connectorId requerido",
                    null, null, null, 0, false, 0);
        }
        // Verificamos sqlExecEnabled antes de delegar (defensa en profundidad:
        // alguien podría intentar pegarle a /execute directo sin pasar por /info).
        BotConnector c = connectorRepo.findById(body.connectorId()).orElse(null);
        if (c == null || !Boolean.TRUE.equals(c.getActive()) || !c.isSqlExecEnabled()) {
            return new SqlExecutionService.ExecResult(
                    false, "DISABLED",
                    "Este conector no admite SQL libre o está inactivo.",
                    null, null, null, 0, false, 0);
        }
        return sqlExec.execute(body.connectorId(), body.sql(), body.sessionId(), body.userQuestion());
    }

    /**
     * Nombre de tool determinístico y seguro para Anthropic (regex
     * {@code ^[a-zA-Z0-9_-]{1,64}$}).
     */
    private String buildToolName(BotConnector c) {
        String slug = c.getName() == null ? "db" : c.getName()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) slug = "db";
        String name = "query_" + c.getId() + "_" + slug;
        return name.length() > 64 ? name.substring(0, 64) : name;
    }
}
