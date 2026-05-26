package app.coincidir.api.coinbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeolocationService — resuelve IP → país/provincia/ciudad usando ip-api.com.
 *
 * ip-api.com es gratis sin registro. Limita a 45 req/minuto desde una sola IP
 * de origen. Como solo lo llamamos cuando se persiste una conversación nueva
 * (no en cada listado), el rate limit alcanza con sobra.
 *
 * Cache LRU en memoria (max 5000 entradas) para evitar resolver la misma IP
 * varias veces dentro del mismo proceso. Si el JAR se reinicia, el cache se
 * pierde — pero las columnas ya están persistidas en BD, así que no importa.
 *
 * IPs privadas (10.x, 172.16-31.x, 192.168.x, 127.x) y localhost devuelven
 * null sin consultar al servicio.
 *
 * Si la resolución falla (timeout, rate limit, IP inválida, error), devuelve
 * un GeoInfo con todos los campos en null. El persist de la conversación NO
 * se bloquea.
 */
@Slf4j
@Service
public class GeolocationService {

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,country,countryCode,regionName,city";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int CACHE_MAX = 5000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, GeoInfo> cache = new ConcurrentHashMap<>();

    /**
     * Resuelve una IP. Best-effort: si falla, devuelve un GeoInfo vacío.
     * Nunca tira excepción — el caller no necesita try/catch.
     */
    public GeoInfo resolve(String ip) {
        if (ip == null || ip.isBlank()) return GeoInfo.empty();

        // IPs privadas o reservadas: no consultar
        if (isPrivateOrReserved(ip)) return GeoInfo.empty();

        // Cache hit
        GeoInfo cached = cache.get(ip);
        if (cached != null) return cached;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(API_URL, ip)))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.debug("[geo] ip-api status {} for {}", resp.statusCode(), ip);
                return cacheAndReturn(ip, GeoInfo.empty());
            }

            JsonNode body = mapper.readTree(resp.body());
            String status = body.path("status").asText("fail");
            if (!"success".equals(status)) {
                return cacheAndReturn(ip, GeoInfo.empty());
            }

            GeoInfo info = new GeoInfo();
            info.country     = textOrNull(body, "country");
            info.countryCode = textOrNull(body, "countryCode");
            info.region      = textOrNull(body, "regionName");
            info.city        = textOrNull(body, "city");
            return cacheAndReturn(ip, info);

        } catch (Exception e) {
            log.debug("[geo] resolve failed for {}: {}", ip, e.getMessage());
            return cacheAndReturn(ip, GeoInfo.empty());
        }
    }

    private GeoInfo cacheAndReturn(String ip, GeoInfo info) {
        // Evict simple si se llena (no es LRU real, es el más viejo en orden de inserción)
        if (cache.size() >= CACHE_MAX) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
        }
        cache.put(ip, info);
        return info;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean isPrivateOrReserved(String ip) {
        if (ip == null) return true;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0.0.0.0")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("169.254.")) return true; // link-local
        if (ip.startsWith("fc") || ip.startsWith("fd")) return true; // IPv6 unique-local
        if (ip.startsWith("fe80:")) return true; // IPv6 link-local
        // 172.16.0.0 — 172.31.255.255
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {
                // IPv4 mal formada o segmento no numérico — caemos al return false
                // de abajo (la IP no está en el rango privado 172.16-31). Silenciado
                // intencional: este flujo no expone "errores reales", solo descarta
                // IPs raras del check de bypass de geo.
            }
        }
        return false;
    }

    /** DTO de salida — todos los campos pueden ser null. */
    public static class GeoInfo {
        public String country;
        public String countryCode;
        public String region;
        public String city;

        public static GeoInfo empty() { return new GeoInfo(); }

        public boolean isEmpty() {
            return country == null && region == null && city == null;
        }
    }
}
