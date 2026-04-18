package app.coincidir.api.web.admin;

import app.coincidir.api.service.DollarQuoteScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint para disparar manualmente la actualización del parámetro COTIZACION_DOLAR
 * desde el Admin Panel (botón "Actualizar Dólar").
 */
@RestController
@RequestMapping("/api/admin/parameters/dollar")
@RequiredArgsConstructor
public class DollarQuoteController {

    private final DollarQuoteScheduler dollarQuoteScheduler;

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh() {
        try {
            DollarQuoteScheduler.UpdateResult res = dollarQuoteScheduler.updateDollarQuote();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", res.ok());
            body.put("value", res.value());
            body.put("previous", res.previous());
            body.put("source", res.source());
            body.put("message", res.message());
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            String m = ex.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "ok", false,
                    "message", (m == null || m.isBlank()) ? "Error interno" : m
            ));
        }
    }
}
