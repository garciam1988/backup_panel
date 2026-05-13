package app.coincidir.api.coinbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AnthropicProxyController — DESHABILITADO desde 2026-05-13.
 *
 * Este proxy permitía que cualquier cliente externo (frontend Capacitor,
 * scripts, etc.) llamara a la API de Anthropic usando la API key del
 * servidor. Al no validar el modelo solicitado ni autenticar al cliente,
 * cualquiera con conocimiento del endpoint podía consumir tokens contra
 * nuestra cuenta — incluyendo modelos caros como claude-sonnet-4-5 o
 * claude-opus-* — sin que aparezca en ningún scheduler del backend.
 *
 * Para reactivar (NO recomendado sin antes endurecerlo):
 *   1. Agregar autenticación obligatoria (JWT del panel admin, no público).
 *   2. Validar que el modelo solicitado esté en una whitelist.
 *   3. Rate limit por IP y por usuario.
 *   4. Loguear cada llamada en api_usage_log (ya existe la tabla).
 *
 * Mientras tanto devuelve 503 Service Unavailable.
 */
@Slf4j
@RestController
@RequestMapping("/api/coinbot/anthropic")
public class AnthropicProxyController {

    @PostMapping("/messages")
    public ResponseEntity<String> proxyMessages(@RequestBody(required = false) String body) {
        log.warn("[AnthropicProxy] Intento de uso del proxy deshabilitado — bodyLen={}",
                body == null ? 0 : body.length());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Anthropic proxy deshabilitado\",\"reason\":\"Endpoint cerrado por control de costos. Contactar al admin para reactivar con autenticación.\"}");
    }
}
