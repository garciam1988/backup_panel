package app.coincidir.api.service;

import app.coincidir.api.domain.logging.ClientLogEvent;
import app.coincidir.api.repository.ClientLogEventRepository;
import app.coincidir.api.web.dto.logging.ClientLogEventDto;
import app.coincidir.api.web.dto.logging.ClientLogsIngestRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClientLogsService {

    private final ClientLogEventRepository repository;
    private final ObjectMapper objectMapper;

    public int ingest(ClientLogsIngestRequest req, HttpServletRequest http) {
        if (req == null || req.logs() == null || req.logs().isEmpty()) {
            return 0;
        }

        int saved = 0;
        int limit = Math.min(req.logs().size(), 200);

        String ip = resolveIp(http);

        for (int i = 0; i < limit; i++) {
            ClientLogsIngestRequest.LogItem li = req.logs().get(i);
            if (li == null) continue;

            String lvl = normalizeLevel(li.level());
            if (!isErrorLevel(lvl)) {
                continue; // persistimos SOLO error/fatal
            }

            Instant clientTs = parseInstant(li.ts());

            String msg = sanitizeString(li.message());
            String dataJson = toSanitizedJson(li.data());
            String breadcrumbsJson = toSanitizedJson(li.breadcrumbs());

            // Truncados defensivos para evitar DataIntegrityViolation por columnas con length
            String app = trimToLenOrNull(req.app(), 80);
            String env = trimToLenOrNull(req.env(), 20);
            String sessionId = trimToLenOrNull(req.sessionId(), 64);
            String category = trimToLenOrNull(li.category(), 40);
            String requestId = trimToLenOrNull(li.requestId(), 80);

            Long userId = req.user() == null ? null : req.user().id();
            String userEmail = req.user() == null ? null : trimToLenOrNull(req.user().email(), 255);
            String userRole = req.user() == null ? null : trimToLenOrNull(req.user().role(), 60);

            String url = req.context() == null ? null : trimToLenOrNull(req.context().url(), 1024);
            String pathname = req.context() == null ? null : trimToLenOrNull(req.context().pathname(), 512);

            String userAgent = req.device() == null ? null : trimToLenOrNull(req.device().userAgent(), 1024);
            String platform = req.device() == null ? null : trimToLenOrNull(req.device().platform(), 120);

            String ipSafe = trimToLenOrNull(ip, 80);

            ClientLogEvent ev = ClientLogEvent.builder()
                    .clientTs(clientTs)
                    .level(trimToLenOrNull(lvl, 10) == null ? "error" : trimToLenOrNull(lvl, 10))
                    .category(category)
                    .app(app)
                    .env(env)
                    .sessionId(sessionId)
                    .requestId(requestId)
                    .userId(userId)
                    .userEmail(userEmail)
                    .userRole(userRole)
                    .url(url)
                    .pathname(pathname)
                    .userAgent(userAgent)
                    .platform(platform)
                    .ip(ipSafe)
                    .message(msg)
                    .dataJson(dataJson)
                    .breadcrumbsJson(breadcrumbsJson)
                    .build();

            try {
                repository.save(ev);
                saved++;
            } catch (Exception ignored) {
                // Fallback ultra conservador: intentamos guardar solo lo mínimo.
                try {
                    ClientLogEvent minimal = ClientLogEvent.builder()
                            .clientTs(clientTs)
                            .level("error")
                            .category(category)
                            .app(app)
                            .env(env)
                            .sessionId(sessionId)
                            .requestId(requestId)
                            .userId(userId)
                            .userEmail(userEmail)
                            .userRole(userRole)
                            .url(url)
                            .pathname(pathname)
                            .userAgent(userAgent)
                            .platform(platform)
                            .ip(ipSafe)
                            .message(msg)
                            .dataJson(null)
                            .breadcrumbsJson(null)
                            .build();
                    repository.save(minimal);
                    saved++;
                } catch (Exception ignored2) {
                    // no-op
                }
            }
        }

        return saved;
    }

    public Page<ClientLogEventDto> search(
            String q,
            String level,
            String category,
            String app,
            String env,
            String requestId,
            Long userId,
            String email,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), clamp(size, 1, 500), Sort.by(Sort.Direction.DESC, "serverTs").and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<ClientLogEvent> spec = buildSpec(q, level, category, app, env, requestId, userId, email, from, to);
        return repository.findAll(spec, pageable).map(this::toDto);
    }

    private Specification<ClientLogEvent> buildSpec(
            String q,
            String level,
            String category,
            String app,
            String env,
            String requestId,
            Long userId,
            String email,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("serverTs"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("serverTs"), to));

            String lvl = trimToNull(level);
            if (lvl != null) ps.add(cb.equal(cb.lower(root.get("level")), lvl.toLowerCase(Locale.ROOT)));

            String cat = trimToNull(category);
            if (cat != null) ps.add(cb.equal(cb.lower(root.get("category")), cat.toLowerCase(Locale.ROOT)));

            String a = trimToNull(app);
            if (a != null) ps.add(cb.equal(cb.lower(root.get("app")), a.toLowerCase(Locale.ROOT)));

            String e = trimToNull(env);
            if (e != null) ps.add(cb.equal(cb.lower(root.get("env")), e.toLowerCase(Locale.ROOT)));

            String rid = trimToNull(requestId);
            if (rid != null) ps.add(cb.equal(root.get("requestId"), rid));

            if (userId != null) ps.add(cb.equal(root.get("userId"), userId));

            String em = trimToNull(email);
            if (em != null) ps.add(cb.equal(cb.lower(root.get("userEmail")), em.toLowerCase(Locale.ROOT)));

            String qq = trimToNull(q);
            if (qq != null) {
                String like = "%" + qq.toLowerCase(Locale.ROOT) + "%";
                Predicate p1 = cb.like(cb.lower(root.get("message")), like);
                Predicate p2 = cb.like(cb.lower(root.get("dataJson")), like);
                Predicate p3 = cb.like(cb.lower(root.get("breadcrumbsJson")), like);
                Predicate p4 = cb.like(cb.lower(root.get("url")), like);
                Predicate p5 = cb.like(cb.lower(root.get("pathname")), like);
                Predicate p6 = cb.like(cb.lower(root.get("requestId")), like);
                Predicate any = cb.or(p1, p2, p3, p4, p5, p6);
                ps.add(any);
            }

            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private ClientLogEventDto toDto(ClientLogEvent e) {
        return new ClientLogEventDto(
                e.getId(),
                e.getServerTs(),
                e.getClientTs(),
                e.getLevel(),
                e.getCategory(),
                e.getApp(),
                e.getEnv(),
                e.getSessionId(),
                e.getRequestId(),
                e.getUserId(),
                e.getUserEmail(),
                e.getUserRole(),
                e.getUrl(),
                e.getPathname(),
                e.getUserAgent(),
                e.getPlatform(),
                e.getIp(),
                e.getMessage(),
                e.getDataJson(),
                e.getBreadcrumbsJson()
        );
    }

    private static boolean isErrorLevel(String lvl) {
        if (lvl == null) return false;
        String l = lvl.toLowerCase(Locale.ROOT);
        return l.equals("error") || l.equals("fatal");
    }

    private static String normalizeLevel(String lvl) {
        String v = trimToNull(lvl);
        if (v == null) return "error";
        v = v.trim().toLowerCase(Locale.ROOT);
        // normalizamos algunos alias comunes
        if (v.equals("err")) return "error";
        if (v.equals("warning")) return "warn";
        return v;
    }

    private Instant parseInstant(String value) {
        String v = trimToNull(value);
        if (v == null) return null;
        try {
            // ISO instant
            return Instant.parse(v);
        } catch (Exception ignored) {
            // fecha simple (YYYY-MM-DD)
            try {
                LocalDate d = LocalDate.parse(v);
                return d.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private String toSanitizedJson(Object obj) {
        if (obj == null) return null;
        Object sanitized = sanitizeObject(obj);
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            return sanitizeString(String.valueOf(sanitized));
        }
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeObject(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Map<?, ?> m) {
            // copiamos para no mutar el original
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            for (var entry : m.entrySet()) {
                String k = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                Object v = entry.getValue();
                if (k == null) continue;
                if (isSensitiveKey(k)) {
                    out.put(k, "****");
                } else {
                    out.put(k, sanitizeObject(v));
                }
            }
            return out;
        }

        if (obj instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(sanitizeObject(v));
            }
            return out;
        }

        if (obj instanceof String s) {
            return sanitizeString(s);
        }

        // para otros tipos (números, boolean, etc.) dejamos igual
        return obj;
    }

    private static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password")
                || k.contains("pass")
                || k.contains("token")
                || k.contains("authorization")
                || k.contains("cookie")
                || k.contains("set-cookie")
                || k.contains("bearer");
    }

    private static String sanitizeString(String s) {
        if (s == null) return null;
        String out = s;
        // Enmascara Authorization: Bearer xxx
        out = out.replaceAll("(?i)authorization\\s*[:=]\\s*bearer\\s+[A-Za-z0-9\\-._~+/]+=*", "Authorization: Bearer ****");
        // Enmascara 'Bearer xxx'
        out = out.replaceAll("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*", "Bearer ****");
        // Enmascara password=...
        out = out.replaceAll("(?i)(password\\s*[:=]\\s*)([^\\s,;]+)", "$1****");
        // Enmascara token=...
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*)([^\\s,;]+)", "$1****");
        return out;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimToLenOrNull(String v, int max) {
        String t = trimToNull(v);
        if (t == null) return null;
        if (t.length() <= max) return t;
        return t.substring(0, max);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String resolveIp(HttpServletRequest req) {
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) return first;
        }
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return req.getRemoteAddr();
    }
}
