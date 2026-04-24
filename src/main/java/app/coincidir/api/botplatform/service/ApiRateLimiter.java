package app.coincidir.api.botplatform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ApiRateLimiter — rate limiting básico in-memory por integration.
 *
 * Se restea cada minuto (ventana fija simple, no sliding). Suficiente para
 * prevenir que Claude dispare 100 llamadas seguidas a una API externa por
 * un loop erróneo.
 *
 * Para distribuir entre múltiples instances del backend hay que pasar esto
 * a Redis, pero por ahora con 1 instance alcanza. Si se escala, se cambia.
 */
@Slf4j
@Component
public class ApiRateLimiter {

    /** Key: integrationId → counter actual + minuto en que arrancó */
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    private static class Bucket {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long windowStartEpochMin;

        Bucket(long minute) { this.windowStartEpochMin = minute; }
    }

    /**
     * Intenta registrar una llamada. Retorna true si está permitida,
     * false si excedió el límite de la ventana actual.
     */
    public boolean tryAcquire(Long integrationId, int maxPerMinute) {
        if (integrationId == null) return true;
        if (maxPerMinute <= 0) maxPerMinute = 60; // default sano

        long currentMin = Instant.now().getEpochSecond() / 60;
        Bucket b = buckets.computeIfAbsent(integrationId, k -> new Bucket(currentMin));

        // Si pasamos a un minuto nuevo, reseteamos
        synchronized (b) {
            if (b.windowStartEpochMin != currentMin) {
                b.windowStartEpochMin = currentMin;
                b.count.set(0);
            }
        }

        int nowCount = b.count.incrementAndGet();
        if (nowCount > maxPerMinute) {
            log.warn("[RateLimit] integration {} bloqueada — {}/{} en esta ventana",
                    integrationId, nowCount, maxPerMinute);
            return false;
        }
        return true;
    }
}
