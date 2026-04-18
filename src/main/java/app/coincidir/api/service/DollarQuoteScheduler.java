package app.coincidir.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Actualiza diariamente el parámetro COTIZACION_DOLAR con el valor
 * del Dólar Blue (venta) tomado de dolarapi.com (Ámbito Financiero como
 * fuente primaria, con fallback a la cotización general).
 *
 * Corre todos los días a las 11:00 AM (hora Argentina).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DollarQuoteScheduler {

    private static final String PARAM_CODE = "COTIZACION_DOLAR";

    // Primario: Ámbito Financiero (solicitado por negocio, coincide con ambito.com)
    private static final String AMBITO_URL = "https://dolarapi.com/v1/ambito/dolares/blue";
    // Fallback: cotización general de dolarapi.com
    private static final String FALLBACK_URL = "https://dolarapi.com/v1/dolares/blue";

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Una vez al día a las 11:00 AM (hora Argentina) */
    @Scheduled(cron = "0 0 11 * * *", zone = "America/Argentina/Buenos_Aires")
    public void run() {
        log.info("[DollarQuoteScheduler] Disparo programado diario 11:00 AM");
        try {
            updateDollarQuote();
        } catch (Exception ex) {
            log.error("[DollarQuoteScheduler] Error en ejecución programada: {}", ex.getMessage(), ex);
        }
    }

    /** Resultado de la actualización, para exponer al FE en la actualización manual. */
    public record UpdateResult(boolean ok, String value, String previous, String source, String message) {}

    /**
     * Lógica principal: consulta la API, toma venta, actualiza parametros.
     * Pública para poder dispararse manualmente desde un endpoint.
     */
    public UpdateResult updateDollarQuote() {
        BigDecimal venta = fetchVenta(AMBITO_URL);
        String source = "ambito";

        if (venta == null) {
            log.warn("[DollarQuoteScheduler] Falló fuente primaria (Ámbito). Intentando fallback.");
            venta = fetchVenta(FALLBACK_URL);
            source = "dolarapi";
        }

        if (venta == null) {
            log.error("[DollarQuoteScheduler] No se pudo obtener la cotización del dólar blue de ninguna fuente.");
            return new UpdateResult(false, null, queryCurrentValue(), null,
                    "No se pudo obtener la cotización del dólar blue");
        }

        // Redondeo a entero (coherente con el formato actual del parámetro: 1450)
        String newValue = venta.setScale(0, RoundingMode.HALF_UP).toPlainString();
        String previous = queryCurrentValue();

        if (newValue.equals(previous)) {
            log.info("[DollarQuoteScheduler] Cotización sin cambios ({}). Fuente: {}", newValue, source);
            return new UpdateResult(true, newValue, previous, source, "Sin cambios");
        }

        int updated = jdbc.update(
                "UPDATE parametros SET value = ? WHERE code = ?",
                newValue, PARAM_CODE
        );

        if (updated == 0) {
            log.warn("[DollarQuoteScheduler] No existe el parámetro {}. No se actualizó nada.", PARAM_CODE);
            return new UpdateResult(false, null, previous, source,
                    "No existe el parámetro " + PARAM_CODE);
        }

        log.info("[DollarQuoteScheduler] {} actualizado: {} -> {} (fuente: {})",
                PARAM_CODE, previous, newValue, source);
        return new UpdateResult(true, newValue, previous, source, "Actualizado");
    }

    private BigDecimal fetchVenta(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "coincidir-api/1.0")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[DollarQuoteScheduler] HTTP {} desde {}", resp.statusCode(), url);
                return null;
            }

            JsonNode node = mapper.readTree(resp.body());
            JsonNode ventaNode = node.get("venta");
            if (ventaNode == null || ventaNode.isNull()) {
                log.warn("[DollarQuoteScheduler] Respuesta sin campo 'venta': {}", resp.body());
                return null;
            }

            BigDecimal venta = ventaNode.decimalValue();
            if (venta == null || venta.signum() <= 0) {
                log.warn("[DollarQuoteScheduler] Valor 'venta' inválido: {}", ventaNode);
                return null;
            }
            return venta;
        } catch (Exception ex) {
            log.warn("[DollarQuoteScheduler] Error consultando {}: {}", url, ex.getMessage());
            return null;
        }
    }

    private String queryCurrentValue() {
        try {
            List<String> values = jdbc.queryForList(
                    "SELECT value FROM parametros WHERE code = ? LIMIT 1",
                    String.class,
                    PARAM_CODE
            );
            return values.isEmpty() ? null : values.get(0);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        } catch (Exception ex) {
            log.warn("[DollarQuoteScheduler] Error leyendo valor actual: {}", ex.getMessage());
            return null;
        }
    }
}
