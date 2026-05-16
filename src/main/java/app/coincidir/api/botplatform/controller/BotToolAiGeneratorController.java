package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.service.BotToolAiGeneratorService;
import app.coincidir.api.botplatform.service.BotToolAiGeneratorService.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BotToolAiGeneratorController — endpoint para generar propuestas de BotTools
 * usando IA, basado en el schema introspectado de un conector.
 *
 * Endpoint:
 *   POST /api/admin/bot-tools/ai/generate-tools
 *     body: {
 *       "connectorId": 1,
 *       "categories": ["TOP_N", "COUNT_KPI", ...],   (opcional, si vacío: todas)
 *       "model": "sonnet" | "haiku"                   (opcional, default sonnet)
 *     }
 *     respuesta: {
 *       "proposals": [
 *         { "name": "...", "category": "...", "description": "...",
 *           "sqlTemplate": "...", "parametersSchemaJson": {...},
 *           "operationType": "QUERY", "connectorId": 1, "rowLimit": 100,
 *           "active": false, "rationale": "..." }
 *       ],
 *       "warnings": ["..."],
 *       "connectorId": 1,
 *       "connectorName": "coincidir",
 *       "tablesAnalyzed": 94
 *     }
 *
 * IMPORTANTE: este endpoint SOLO devuelve propuestas. NO crea BotTools en la BD.
 * El frontend muestra las propuestas, el admin elige cuáles aprobar/editar, y
 * después llama al endpoint existente POST /api/admin/bot-tools por cada una.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-tools/ai")
@RequiredArgsConstructor
public class BotToolAiGeneratorController {

    private final BotToolAiGeneratorService generatorService;

    public record GenerateRequest(
            Long connectorId,
            List<String> categories,
            String model
    ) {}

    @PostMapping("/generate-tools")
    public Map<String, Object> generate(@RequestBody GenerateRequest req, Authentication auth) {
        if (req == null || req.connectorId() == null) {
            throw new IllegalArgumentException("connectorId requerido");
        }

        // Parsear categorías (case-insensitive). Si lista vacía, el service usa todas.
        List<Category> cats = new ArrayList<>();
        if (req.categories() != null) {
            for (String c : req.categories()) {
                if (c == null) continue;
                try {
                    cats.add(Category.valueOf(c.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log.warn("[tool-gen] categoría desconocida ignorada: {}", c);
                }
            }
        }

        String user = auth != null ? auth.getName() : "?";
        log.info("[tool-gen] request connectorId={} cats={} model={} user={}",
                req.connectorId(), cats, req.model(), user);

        return generatorService.generate(req.connectorId(), cats, req.model());
    }
}
