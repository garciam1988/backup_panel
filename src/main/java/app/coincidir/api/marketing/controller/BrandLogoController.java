package app.coincidir.api.marketing.controller;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Optional;

/**
 * Sirve el logo del bot_config como recurso HTTP público.
 *
 * Endpoint: GET /api/public/loyalty/brand-logo
 *
 * Casos de uso:
 *  - Templates de email (HTML): &lt;img src="https://api/.../brand-logo"&gt;
 *    Mucho mejor que pegar un data URL de varios kb en el HTML del email.
 *  - PWA del cliente como fallback.
 *  - Cualquier integración que necesite mostrar el branding del cliente.
 *
 * Comportamiento:
 *  - Si bot_config.logoUrl es un data URL (data:image/png;base64,XXX),
 *    decodifica el base64 y sirve los bytes con el content-type correcto.
 *  - Si es una URL http(s) externa, hace 302 redirect a ese URL.
 *  - Si no hay logo configurado, devuelve 404.
 *
 * Multi-tenant: cada cliente tiene su propio bot_config (singleton id=1 por
 * tenant). Como el backend es por-cliente (un Railway service por tenant),
 * cada endpoint devuelve automáticamente el logo del cliente que está
 * sirviendo. No requiere parámetros.
 *
 * Cacheo: response con Cache-Control: public, max-age=3600 (1 hora). Esto
 * es importante para no procesar el base64 en cada open de email — los
 * clientes de email cachean igualmente con Content-Type correcto.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/loyalty")
@RequiredArgsConstructor
public class BrandLogoController {

    private final BotConfigRepository botConfigRepository;

    @GetMapping("/brand-logo")
    public ResponseEntity<?> getBrandLogo() {
        Optional<BotConfig> maybe = botConfigRepository.findById(1L);
        if (maybe.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String logoUrl = maybe.get().getLogoUrl();
        if (logoUrl == null || logoUrl.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        // Caso 1: URL externa — redirigimos. El cliente de email va a hacer
        // la request a ese URL directamente. Esto soporta el caso donde el
        // dueño cargó un link en lugar de subir un archivo.
        if (logoUrl.startsWith("http://") || logoUrl.startsWith("https://")) {
            return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, logoUrl)
                .build();
        }

        // Caso 2: data URL (data:image/png;base64,XXXXX). Lo más común.
        // Decodificamos el base64 y servimos los bytes.
        if (logoUrl.startsWith("data:")) {
            try {
                int commaIdx = logoUrl.indexOf(",");
                if (commaIdx < 0) {
                    log.warn("Logo data URL malformada (sin coma): primeros 40 chars: {}",
                        logoUrl.substring(0, Math.min(40, logoUrl.length())));
                    return ResponseEntity.notFound().build();
                }
                String header = logoUrl.substring(0, commaIdx); // "data:image/png;base64"
                String payload = logoUrl.substring(commaIdx + 1);

                // Sacamos el content-type del header (entre "data:" y ";")
                String contentType = "image/png"; // default razonable
                int semicolonIdx = header.indexOf(";");
                if (semicolonIdx > 5) {
                    contentType = header.substring(5, semicolonIdx); // "image/png"
                } else if (header.length() > 5) {
                    contentType = header.substring(5);
                }

                byte[] bytes = Base64.getDecoder().decode(payload);

                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"logo\"")
                    .body(bytes);
            } catch (IllegalArgumentException e) {
                log.warn("Logo data URL con base64 inválido: {}", e.getMessage());
                return ResponseEntity.notFound().build();
            } catch (Exception e) {
                log.error("Error sirviendo logo del bot_config", e);
                return ResponseEntity.internalServerError().build();
            }
        }

        // Caso 3: cualquier otra cosa (path raro, string vacío disfrazado).
        // No sabemos qué hacer con eso — 404 limpio.
        return ResponseEntity.notFound().build();
    }
}
