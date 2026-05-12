package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ConnectorTableDescription;
import app.coincidir.api.botplatform.repository.ConnectorTableDescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Endpoints admin para gestionar las descripciones por tabla de un conector
 * (Fase 5 — comprensión semántica del schema).
 *
 * Diseño REST:
 *   GET    /api/admin/bot-connectors/{id}/table-descriptions
 *      → lista de {tableName, description} ordenada alfabéticamente.
 *
 *   PUT    /api/admin/bot-connectors/{id}/table-descriptions
 *      → reemplaza el bulk completo. Body: [{tableName, description}, ...]
 *        Pensado para guardar todo el form de una sola vez (en vez de
 *        N PUTs por tabla). Si una entrada tiene description vacía, la
 *        eliminamos (no guardamos descripciones vacías).
 *
 * No tenemos DELETE por tabla puntual — el PUT bulk ya cubre todos los
 * casos (incluido borrar uno).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-connectors")
@RequiredArgsConstructor
public class TableDescriptionController {

    private final ConnectorTableDescriptionRepository repo;
    private final app.coincidir.api.botplatform.service.SchemaIntrospectionService schemaService;

    @GetMapping("/{connectorId}/table-descriptions")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@PathVariable Long connectorId) {
        return repo.findByConnectorIdOrderByTableNameAsc(connectorId).stream()
                .map(this::toDto)
                .toList();
    }

    public record TableDescItem(String tableName, String description) {}

    @PutMapping("/{connectorId}/table-descriptions")
    @Transactional
    public Map<String, Object> updateBulk(@PathVariable Long connectorId,
                                          @RequestBody List<TableDescItem> items) {
        if (items == null) items = List.of();

        // Estrategia: upsert por (connectorId, tableName).
        //   - Si description está vacía → borrar la fila si existe.
        //   - Si description tiene contenido → crear o actualizar.
        //
        // No hacemos delete-all-and-recreate para evitar IDs siempre nuevos
        // (no es crítico, pero es prolijo: las descripciones tienen id
        // estable mientras tengan contenido). Además, delete-all-recreate
        // dentro de una misma transacción puede tener issues con la
        // unique constraint en algunos motores.

        int updated = 0;
        int created = 0;
        int deleted = 0;

        for (TableDescItem item : items) {
            if (item == null || item.tableName() == null) continue;
            String table = item.tableName().trim().toLowerCase(Locale.ROOT);
            if (table.isEmpty()) continue;
            String desc = item.description() == null ? "" : item.description().trim();

            var existing = repo.findByConnectorIdAndTableName(connectorId, table).orElse(null);

            if (desc.isEmpty()) {
                if (existing != null) {
                    repo.delete(existing);
                    deleted++;
                }
                continue;
            }

            if (existing != null) {
                existing.setDescription(desc);
                existing.setUpdatedAt(Instant.now());
                repo.save(existing);
                updated++;
            } else {
                ConnectorTableDescription row = new ConnectorTableDescription();
                row.setConnectorId(connectorId);
                row.setTableName(table);
                row.setDescription(desc);
                repo.save(row);
                created++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectorId", connectorId);
        out.put("created", created);
        out.put("updated", updated);
        out.put("deleted", deleted);
        out.put("total", created + updated);

        // Regenerar el llmSummary cacheado para que el bot vea los cambios
        // en la próxima conversación. Best-effort: si falla, los cambios
        // quedan en la BD igual y se reflejan al próximo re-scan.
        try {
            schemaService.regenerateLlmSummary(connectorId);
        } catch (Exception ex) {
            log.warn("No pude regenerar llmSummary para connector {} tras update bulk de descripciones: {}",
                    connectorId, ex.getMessage());
        }

        return out;
    }

    private Map<String, Object> toDto(ConnectorTableDescription d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("tableName", d.getTableName());
        m.put("description", d.getDescription());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
