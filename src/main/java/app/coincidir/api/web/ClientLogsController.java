package app.coincidir.api.web;

import app.coincidir.api.service.ClientLogsService;
import app.coincidir.api.web.dto.logging.ClientLogEventDto;
import app.coincidir.api.web.dto.logging.ClientLogsIngestRequest;
import app.coincidir.api.web.dto.logging.ClientLogsIngestResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/client-logs")
public class ClientLogsController {

    private final ClientLogsService service;

    @PostMapping
    public ClientLogsIngestResponse ingest(@RequestBody(required = false) ClientLogsIngestRequest req,
                                          HttpServletRequest http) {
        try {
            int saved = service.ingest(req, http);
            return new ClientLogsIngestResponse(saved);
        } catch (Exception ignored) {
            // Nunca devolvemos 500 por el colector: no debe generar loops en el frontend.
            return new ClientLogsIngestResponse(0);
        }
    }

    @GetMapping
    public Page<ClientLogEventDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Instant fromTs = parseInstant(from);
        Instant toTs = parseInstant(to);
        return service.search(q, level, category, app, env, requestId, userId, email, fromTs, toTs, page, size);
    }

    private Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.parse(v.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
