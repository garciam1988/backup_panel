package app.coincidir.api.service;

import app.coincidir.api.domain.logging.ClientLogEvent;
import app.coincidir.api.repository.ClientLogEventRepository;
import app.coincidir.api.web.dto.clientlogs.ClientLogIngestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClientLogService {

    private final ClientLogEventRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_LOGS_PER_PAYLOAD = 50;

    public int ingest(HttpServletRequest httpReq, ClientLogIngestRequest req) {
        if (req == null || req.getLogs() == null || req.getLogs().isEmpty()) return 0;

        final List<ClientLogIngestRequest.ClientLogEntryDto> incoming = req.getLogs();
        final int limit = Math.min(incoming.size(), MAX_LOGS_PER_PAYLOAD);

        final List<ClientLogEvent> toSave = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            var e = incoming.get(i);
            final String level = safeLower(e.getLevel());
            if (!"error".equals(level) && !"fatal".equals(level)) continue; // persistimos SOLO errores

            ClientLogEvent event = ClientLogEvent.builder()
                    .clientTs(parseInstant(e.getTs()))
                    .level(level)
                    .category(safeTrim(e.getCategory(), 40))
                    .app(safeTrim(req.getApp(), 80))
                    .env(safeTrim(req.getEnv(), 20))
                    .sessionId(safeTrim(req.getSessionId(), 64))
                    .requestId(safeTrim(e.getRequestId(), 80))
                    .userId(safeUserId(req))
                    .userEmail(safeTrim(req.getUser() != null ? req.getUser().getEmail() : null, 255))
                    .userRole(safeTrim(req.getUser() != null ? req.getUser().getRole() : null, 60))
                    .url(safeTrim(sanitize(req.getContext() != null ? req.getContext().getUrl() : null), 1024))
                    .pathname(safeTrim(req.getContext() != null ? req.getContext().getPathname() : null, 512))
                    .userAgent(safeTrim(sanitize(req.getDevice() != null ? req.getDevice().getUserAgent() : null), 1024))
                    .platform(safeTrim(req.getDevice() != null ? req.getDevice().getPlatform() : null, 120))
                    .ip(safeTrim(httpReq != null ? httpReq.getRemoteAddr() : null, 80))
                    .message(sanitize(e.getMessage()))
                    .dataJson(sanitize(toJson(e.getData())))
                    .breadcrumbsJson(sanitize(toJson(e.getBreadcrumbs())))
                    .build();

            toSave.add(event);
        }

        if (toSave.isEmpty()) return 0;
        repo.saveAll(toSave);
        return toSave.size();
    }

    public Page<ClientLogEvent> search(
            Instant from,
            Instant to,
            String level,
            String category,
            String userEmail,
            String requestId,
            String contains,
            String app,
            String env,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "serverTs").and(Sort.by(Sort.Direction.DESC, "id")));

        Specification<ClientLogEvent> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();

            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("serverTs"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("serverTs"), to));

            if (level != null && !level.isBlank()) ps.add(cb.equal(root.get("level"), safeLower(level)));
            if (category != null && !category.isBlank()) ps.add(cb.equal(root.get("category"), category));
            if (app != null && !app.isBlank()) ps.add(cb.equal(root.get("app"), app));
            if (env != null && !env.isBlank()) ps.add(cb.equal(root.get("env"), env));

            if (userEmail != null && !userEmail.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("userEmail")), "%" + userEmail.toLowerCase() + "%"));
            }
            if (requestId != null && !requestId.isBlank()) ps.add(cb.equal(root.get("requestId"), requestId));

            if (contains != null && !contains.isBlank()) {
                String like = "%" + contains + "%";
                ps.add(cb.or(
                        cb.like(root.get("message"), like),
                        cb.like(root.get("dataJson"), like),
                        cb.like(root.get("breadcrumbsJson"), like)
                ));
            }

            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return repo.findAll(spec, pageable);
    }

    private String safeLower(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }

    private String safeTrim(String s, int maxLen) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }

    private Long safeUserId(ClientLogIngestRequest req) {
        try {
            if (req == null || req.getUser() == null || req.getUser().getId() == null) return null;
            Object id = req.getUser().getId();
            if (id instanceof Number n) return n.longValue();
            String s = String.valueOf(id).trim();
            if (s.isBlank()) return null;
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception ignored) {
            return String.valueOf(o);
        }
    }

    private String sanitize(String s) {
        if (s == null) return null;
        try {
            return s
                    .replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-\\._~\\+\\/]+=*", "Bearer ****")
                    .replaceAll("(?i)\\\"password\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"password\\\":\\\"****\\\"")
                    .replaceAll("(?i)\\\"authorization\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"authorization\\\":\\\"****\\\"")
                    .replaceAll("(?i)\\\"token\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"token\\\":\\\"****\\\"");
        } catch (Exception ignored) {
            return s;
        }
    }
}
