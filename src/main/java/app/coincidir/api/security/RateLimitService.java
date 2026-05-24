package app.coincidir.api.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio centralizado de rate limiting y anti-abuso.
 *
 * Maneja TODAS las protecciones contra abuso del bot público:
 *
 * 1) Rate limit GENERAL por IP — ventana de 60s, máximo configurable (default 60).
 *    Aplica a cualquier endpoint /api/public/** o /api/coinbot/**.
 *
 * 2) Burst protection por IP — ventana de 10s, máximo 15. Detecta scripts
 *    martillando el endpoint sin penalizar uso legítimo (un usuario humano
 *    nunca dispara 15 requests en 10s).
 *
 * 3) Límite de escrituras por IP — ventana de 1 hora, máximo 10. Aplica
 *    SOLO a operaciones destructivas (add_record, update_record, delete_record,
 *    voucher_emit, etc). Si alguien encuentra el endpoint, no puede llenarte
 *    la BD de basura.
 *
 * 4) Rate limit por sessionId — ventana de 1 hora, máximo 50. Aplica al chat
 *    AI. Bloquea a alguien que use proxies rotativos para evadir el límite
 *    por IP, manteniendo la misma sesión.
 *
 * 5) Ban temporal de IPs ofensivas — cuando una IP es rechazada N veces
 *    seguidas, se la mete en blacklist por 5 minutos. Ahorra CPU bajo
 *    ataque sostenido (rechazamos sin ni siquiera evaluar el rate check).
 *
 * Implementación en memoria — sin Redis ni cache externa. Vive en la JVM del
 * backend. Suficiente para un servicio single-instance como el actual. Si
 * algún día escalamos a multi-instance horizontal, hay que migrar a Redis
 * (los contadores deben ser compartidos entre instancias).
 *
 * Limpieza: cada 5 minutos un @Scheduled vacía buckets vacíos y bans
 * expirados. Evita fuga de memoria con IPs viejas.
 */
@Service
@Slf4j
public class RateLimitService {

    // ── Límites configurados ────────────────────────────────────────────
    //
    // Los valores están calibrados para tolerar el bootstrap del bot (que
    // dispara 20+ requests en paralelo al cargar) y el polling normal del
    // frontend (health, proactive-messages, check-command, etc) sin
    // penalizar uso legítimo, pero cortando ataques reales (cientos de
    // requests por segundo).
    //
    // Si en producción ves que tu propio bot se banea por tráfico legítimo,
    // subí los límites GENERAL o BURST. Si querés más protección, bajalos.

    /** Ventana general: 60 segundos. */
    private static final long WINDOW_GENERAL_MS = Duration.ofSeconds(60).toMillis();
    /** Máximo en la ventana general: 200 req/min por IP.
     *  Una conversación humana con polling cada 10s gasta ~12/min solo en
     *  polling. Una conversación activa con varias acciones puede llegar
     *  a 30-50/min. 200 da margen 4x para uso humano normal. */
    private static final int  MAX_GENERAL = 200;

    /** Ventana burst: 10 segundos. */
    private static final long WINDOW_BURST_MS = Duration.ofSeconds(10).toMillis();
    /** Máximo en la ventana burst: 40 req/10s por IP.
     *  El bootstrap del bot dispara ~20-25 requests en paralelo cuando se
     *  carga la página (config, prompts, tools, branches, menú, etc).
     *  40 cubre con margen sin permitir burst abusivo (un humano nunca
     *  hace 40 clicks en 10s). */
    private static final int  MAX_BURST = 40;

    /** Ventana de escrituras: 1 hora. */
    private static final long WINDOW_WRITES_MS = Duration.ofHours(1).toMillis();
    /** Máximo de escrituras: 10/hora por IP. (Igual que antes — sigue siendo
     *  el corte clave contra spam de reservas falsas.) */
    private static final int  MAX_WRITES = 10;

    /** Ventana sessionId: 1 hora. */
    private static final long WINDOW_SESSION_MS = Duration.ofHours(1).toMillis();
    /** Máximo por sesión: 50 mensajes/hora. */
    private static final int  MAX_SESSION = 50;

    /** Strikes antes del ban temporal.
     *  Es defensivo: solo se incrementa cuando una IP excede el límite
     *  GENERAL (no el burst — ver checkGeneral). Un humano normal puede
     *  excederse ocasionalmente; 10 strikes consecutivos sí es ataque. */
    private static final int  STRIKES_BEFORE_BAN = 10;
    /** Tiempo de ban temporal. Reducido a 2 min para que un falso positivo
     *  no deje al cliente fuera mucho rato — si fue real ataque, el atacante
     *  vuelve y se vuelve a banear, no importa. */
    private static final long BAN_DURATION_MS = Duration.ofMinutes(2).toMillis();

    /** Límite duro del tamaño de los maps — si llegamos acá hay un problema. */
    private static final int MAX_TRACKED_IPS = 50_000;
    private static final int MAX_TRACKED_SESSIONS = 10_000;

    // ── Estado en memoria ───────────────────────────────────────────────

    /** Bucket por IP: timestamps de cada request reciente (para windowed counting). */
    private final Map<String, Deque<Long>> generalBuckets = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> burstBuckets   = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> writeBuckets   = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> sessionBuckets = new ConcurrentHashMap<>();

    /** Strike counter por IP (rechazos consecutivos). Se resetea cuando pasa un
     *  período sin rechazos o cuando se efectiva el ban. */
    private final Map<String, AtomicInteger> strikeCounters = new ConcurrentHashMap<>();

    /** IPs baneadas → timestamp de expiración del ban. */
    private final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();

    // ── API pública ─────────────────────────────────────────────────────

    /**
     * Chequea si una request general (lectura) de esta IP está permitida.
     *
     * Aplica los tres niveles:
     *   1. ¿La IP está baneada? → rechazo inmediato.
     *   2. ¿Excede el burst (15/10s)? → rechazo + strike.
     *   3. ¿Excede el general (60/min)? → rechazo + strike.
     *
     * Si todo pasa, registra la request en ambos buckets y devuelve `ALLOWED`.
     *
     * @return resultado con flag + segundos para `Retry-After` si aplica.
     */
    public Decision checkGeneral(String ip) {
        if (ip == null || ip.isBlank()) return Decision.ALLOWED;

        Long banExpiry = bannedUntil.get(ip);
        long now = System.currentTimeMillis();
        if (banExpiry != null) {
            if (banExpiry > now) {
                return new Decision(false, (int) ((banExpiry - now) / 1000) + 1, "ip_banned");
            } else {
                // Ban expirado, lo limpiamos
                bannedUntil.remove(ip);
                strikeCounters.remove(ip);
            }
        }

        // Burst check primero — la ventana más corta, más rápida de evaluar.
        // IMPORTANTE: si pasa el burst pero está dentro del general, NO le
        // damos strike. El burst se excede legítimamente cuando una página
        // hace bootstrap de muchos recursos en paralelo. Solo el general
        // (sostenido en el minuto) indica abuso real.
        if (!recordInBucket(burstBuckets, ip, now, WINDOW_BURST_MS, MAX_BURST)) {
            return new Decision(false, 10, "burst_exceeded");
        }
        if (!recordInBucket(generalBuckets, ip, now, WINDOW_GENERAL_MS, MAX_GENERAL)) {
            recordStrike(ip);
            return new Decision(false, 60, "rate_exceeded");
        }

        // Request OK — reseteamos strikes (no acumulan si la IP se porta bien).
        AtomicInteger sc = strikeCounters.get(ip);
        if (sc != null) sc.set(0);
        return Decision.ALLOWED;
    }

    /**
     * Chequea específicamente una operación de ESCRITURA por IP.
     *
     * Se llama ADEMÁS del checkGeneral, no en su lugar — primero el filtro
     * global aplica los límites generales, después este se aplica si la
     * request resulta ser write.
     *
     * Una IP puede hacer 60 lecturas/min pero solo 10 escrituras/hora.
     */
    public Decision checkWrite(String ip) {
        if (ip == null || ip.isBlank()) return Decision.ALLOWED;
        long now = System.currentTimeMillis();
        if (!recordInBucket(writeBuckets, ip, now, WINDOW_WRITES_MS, MAX_WRITES)) {
            // No damos strike acá — un cliente que legítimamente intenta
            // hacer 11 reservas en una hora no es atacante. El strike y
            // ban automático están reservados para el caso de abuso
            // sostenido sobre el endpoint general (cientos de requests/min).
            return new Decision(false, 3600, "write_limit_exceeded");
        }
        return Decision.ALLOWED;
    }

    /**
     * Chequea el límite por sessionId del chat AI.
     *
     * Lo llama BotAiController después de parsear el body (necesita extraer
     * el sessionId del JSON, el filtro genérico no puede hacerlo).
     */
    public Decision checkSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Decision.ALLOWED;
        // Cap defensivo: si tenemos demasiadas sesiones trackeadas, paramos
        // de aceptar nuevas — algo raro está pasando (sessionIds aleatorios?).
        if (sessionBuckets.size() > MAX_TRACKED_SESSIONS && !sessionBuckets.containsKey(sessionId)) {
            log.warn("[RateLimit] demasiadas sesiones trackeadas ({}), rechazando nuevas",
                    sessionBuckets.size());
            return new Decision(false, 60, "too_many_sessions");
        }
        long now = System.currentTimeMillis();
        if (!recordInBucket(sessionBuckets, sessionId, now, WINDOW_SESSION_MS, MAX_SESSION)) {
            return new Decision(false, 3600, "session_limit_exceeded");
        }
        return Decision.ALLOWED;
    }

    // ── Lógica interna ──────────────────────────────────────────────────

    /**
     * Registra un timestamp en un bucket si no excede el max permitido en la
     * ventana. Devuelve `true` si se pudo registrar, `false` si está saturado.
     *
     * Usa synchronized sobre el bucket — concurrencia segura entre múltiples
     * requests de la MISMA IP. Diferentes IPs no se bloquean entre sí porque
     * cada una tiene su propio bucket.
     */
    private boolean recordInBucket(Map<String, Deque<Long>> map, String key,
                                   long now, long windowMs, int max) {
        // Cap defensivo en cantidad de IPs trackeadas
        if (map.size() > MAX_TRACKED_IPS && !map.containsKey(key)) {
            log.warn("[RateLimit] demasiadas IPs trackeadas ({}), rechazando nuevas",
                    map.size());
            return false;
        }
        Deque<Long> bucket = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            long cutoff = now - windowMs;
            // Sacamos los timestamps fuera de la ventana
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.pollFirst();
            }
            if (bucket.size() >= max) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }

    /**
     * Registra un "strike" para una IP. Si llega a STRIKES_BEFORE_BAN, la
     * IP se banea por BAN_DURATION_MS minutos.
     */
    private void recordStrike(String ip) {
        AtomicInteger sc = strikeCounters.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int n = sc.incrementAndGet();
        if (n >= STRIKES_BEFORE_BAN) {
            long expiry = System.currentTimeMillis() + BAN_DURATION_MS;
            bannedUntil.put(ip, expiry);
            sc.set(0);
            log.warn("[RateLimit] IP baneada por abuso: {} (expira en {}min)",
                    ip, BAN_DURATION_MS / 60_000);
        }
    }

    /**
     * Cleanup periódico: cada 5 minutos vaciamos buckets vacíos y bans
     * expirados. Sin esto, una IP que pasa una vez y nunca más vuelve queda
     * ocupando memoria para siempre.
     */
    @Scheduled(fixedDelay = 300_000) // 5 min
    public void cleanup() {
        long now = System.currentTimeMillis();
        int removedBuckets = 0;
        removedBuckets += cleanBuckets(generalBuckets, now);
        removedBuckets += cleanBuckets(burstBuckets, now);
        removedBuckets += cleanBuckets(writeBuckets, now);
        removedBuckets += cleanBuckets(sessionBuckets, now);
        int removedBans = 0;
        for (Map.Entry<String, Long> e : bannedUntil.entrySet()) {
            if (e.getValue() < now) {
                bannedUntil.remove(e.getKey());
                removedBans++;
            }
        }
        if (removedBuckets > 0 || removedBans > 0) {
            log.debug("[RateLimit] cleanup: -{} buckets, -{} bans expirados",
                    removedBuckets, removedBans);
        }
    }

    private int cleanBuckets(Map<String, Deque<Long>> map, long now) {
        int removed = 0;
        // Calculamos un cutoff generoso (1 hora) — si el bucket no tuvo
        // actividad en la última hora, lo borramos. Es seguro porque las
        // ventanas más grandes son de 1 hora exactamente.
        long cutoff = now - Duration.ofHours(1).toMillis();
        for (Map.Entry<String, Deque<Long>> e : map.entrySet()) {
            Deque<Long> b = e.getValue();
            synchronized (b) {
                if (b.isEmpty() || b.peekLast() < cutoff) {
                    map.remove(e.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Resultado del chequeo. */
    public static final class Decision {
        public static final Decision ALLOWED = new Decision(true, 0, null);
        public final boolean allowed;
        public final int retryAfterSeconds;
        public final String reason;

        public Decision(boolean allowed, int retryAfterSeconds, String reason) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.reason = reason;
        }
    }
}
