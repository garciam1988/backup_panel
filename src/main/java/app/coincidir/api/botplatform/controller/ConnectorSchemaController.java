package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import app.coincidir.api.botplatform.service.SchemaIntrospectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ConnectorSchemaController — endpoints para introspeccionar y consultar
 * el esquema de un BotConnector.
 *
 *   POST /api/admin/bot-connectors/{id}/introspect → escanea la BD y guarda
 *   GET  /api/admin/bot-connectors/{id}/schema     → devuelve el snapshot guardado
 *
 * Por qué endpoints separados:
 *   - introspect puede tardar (varios segundos en BDs grandes); el admin lo
 *     dispara explícitamente desde un botón "Re-escanear esquema".
 *   - schema es rápido (lee del cache) y se usa cada vez que el admin abre
 *     la pantalla del conector para mostrarle al cliente el mapa de su BD.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-connectors")
@RequiredArgsConstructor
public class ConnectorSchemaController {

    private final SchemaIntrospectionService introspectService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @PostMapping("/{id}/introspect")
    public Map<String, Object> introspect(@PathVariable Long id) {
        try {
            ConnectorSchemaCache cache = introspectService.introspect(id);
            return toResponseDto(cache, /*includeSchema*/ false);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("[introspect] error introspectando conector {}: {}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo introspectar el conector: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/schema")
    public Map<String, Object> getSchema(@PathVariable Long id,
                                         @RequestParam(defaultValue = "true") boolean includeSchema) {
        return introspectService.getCached(id)
                .map(cache -> toResponseDto(cache, includeSchema))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Este conector todavía no fue introspectado. Llamá primero a POST /introspect."));
    }

    private Map<String, Object> toResponseDto(ConnectorSchemaCache cache, boolean includeSchema) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cache.getId());
        out.put("connectorId", cache.getConnectorId());
        out.put("tableCount", cache.getTableCount());
        out.put("columnCount", cache.getColumnCount());
        out.put("refreshedAt", cache.getRefreshedAt());
        out.put("llmSummary", cache.getLlmSummary());
        if (includeSchema && cache.getSchemaJson() != null) {
            try {
                Object schemaTree = jsonMapper.readValue(cache.getSchemaJson(), Object.class);
                out.put("schema", schemaTree);
            } catch (Exception e) {
                out.put("schema", null);
                out.put("schemaParseError", e.getMessage());
            }
        }
        return out;
    }
}
