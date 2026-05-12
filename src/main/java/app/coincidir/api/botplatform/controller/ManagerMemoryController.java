package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.service.ManagerMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints para la memoria conversacional del bot Manager.
 *
 * Hay dos grupos:
 *
 *   PÚBLICOS (consumidos por el CoinBot frontend cuando habla el Gerente):
 *     - POST /api/public/manager-memory/turn        → procesa un turno completo
 *     - POST /api/public/manager-memory/check-command → detecta recordá/olvidá
 *     - GET  /api/public/manager-memory/section     → bloque de memoria para system prompt
 *
 *   ADMIN (para la futura UI admin de revisión):
 *     - GET /api/admin/manager-memory      → listar todas las memorias
 *     - DELETE /api/admin/manager-memory/{id} → borrar una específica
 *     - DELETE /api/admin/manager-memory   → borrar todas (con confirm)
 *
 * NOTA DE SEGURIDAD: los endpoints "públicos" tienen ese nombre porque viven
 * en /api/public/ (que el CoinBot frontend puede llamar sin JWT del admin),
 * pero deberían estar protegidos por la lógica de identificación del Gerente
 * que ya existe en el bot. Como este bot es de uso exclusivo del Gerente
 * General (según el prompt principal), no agregamos auth extra acá.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ManagerMemoryController {

    private final ManagerMemoryService service;

    // ─────────────────────────────────────────────────────────────────────
    // PÚBLICOS (CoinBot)
    // ─────────────────────────────────────────────────────────────────────

    public record TurnRequest(String userMessage, String botResponse, String sessionId) {}

    /**
     * Procesa un turno completo. Extrae hechos vía Haiku y los guarda como
     * memorias automáticas. Es asíncrono — devuelve inmediatamente.
     */
    @PostMapping("/api/public/manager-memory/turn")
    public Map<String, Object> processTurn(@RequestBody TurnRequest body) {
        if (body != null) {
            service.processTurnAsync(
                    body.userMessage(),
                    body.botResponse(),
                    body.sessionId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("queued", true);
        return out;
    }

    public record CommandRequest(String userMessage, String sessionId) {}

    /**
     * Detecta comandos explícitos en el mensaje del usuario antes de pasar
     * al flujo normal del bot. Si detecta "recordá esto..." o "olvidá X",
     * lo procesa y devuelve un acknowledgment para que el bot responda eso
     * en vez de hacer la query normal.
     *
     * Respuesta:
     *   { "handled": true, "ack": "Listo, lo recordé." }     ← bot debería usar este texto
     *   { "handled": false }                                  ← seguir flujo normal
     */
    @PostMapping("/api/public/manager-memory/check-command")
    public Map<String, Object> checkCommand(@RequestBody CommandRequest body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null || body.userMessage() == null) {
            out.put("handled", false);
            return out;
        }
        ManagerMemoryService.CommandResult res = service.detectExplicitCommands(
                body.userMessage(), body.sessionId());
        if (res == null) {
            out.put("handled", false);
            return out;
        }
        out.put("handled", res.skipNormalProcessing());
        out.put("ack", res.acknowledgment());
        return out;
    }

    /**
     * Devuelve el bloque de texto con las memorias activas listas para
     * inyectar al system prompt. Si no hay memorias, devuelve string vacío.
     */
    @GetMapping("/api/public/manager-memory/section")
    public Map<String, Object> memorySection() {
        String section = service.buildMemorySection();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("section", section);
        out.put("hasMemory", !section.isEmpty());
        return out;
    }
}
