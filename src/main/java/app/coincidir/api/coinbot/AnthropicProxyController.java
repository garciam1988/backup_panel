package app.coincidir.api.coinbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AnthropicProxyController — Proxy server-side para llamadas a la API de Anthropic.
 *
 * Permite que el frontend (app móvil Capacitor) llame a Anthropic sin exponer
 * la API key en el cliente ni sufrir bloqueos CORS de Anthropic.
 *
 * Endpoint:
 *   POST /api/coinbot/anthropic/messages → proxy a https://api.anthropic.com/v1/messages
 *
 * La API key se configura en application.yml:
 *   coincidir:
 *     anthropic-key: sk-ant-api03-...
 * O por variable de entorno: ANTHROPIC_KEY
 */
@Slf4j
@RestController
@RequestMapping("/api/coinbot/anthropic")
public class AnthropicProxyController {

    @Value("${coincidir.anthropic-key:}")
    private String anthropicKey;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    @PostMapping("/messages")
    public ResponseEntity<String> proxyMessages(@RequestBody String body) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            log.error("[AnthropicProxy] API key no configurada");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Anthropic API key no configurada en el servidor\"}");
        }

        log.info("[AnthropicProxy] POST /messages — forwarding to Anthropic");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("[AnthropicProxy] Anthropic response status={}", response.statusCode());

            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());

        } catch (Exception e) {
            log.error("[AnthropicProxy] Error llamando a Anthropic: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Error comunicando con Anthropic: " + e.getMessage() + "\"}");
        }
    }
}
