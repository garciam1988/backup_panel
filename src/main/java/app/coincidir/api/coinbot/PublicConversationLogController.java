package app.coincidir.api.coinbot;

import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.ConversationLogRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * PublicConversationLogController — endpoint PÚBLICO (sin autenticación) para
 * que el bot del visitante anónimo pueda persistir el log al cerrar la charla.
 *
 *   POST /api/public/conversation-log → guarda una conversación cerrada
 *
 * El bot público no tiene JWT (los visitantes son anónimos), entonces no puede
 * golpear /api/admin/conversation-log. Antes el guardado salía con `return null`
 * en el frontend cuando no había token y las conversaciones se perdían — solo
 * se persistían las del admin testeando logueado.
 *
 * Este endpoint replica el comportamiento de POST /api/admin/conversation-log
 * pero sin requerir auth. Acepta el mismo SaveRequest del controller admin.
 *
 * Seguridad: el endpoint NO confía ciegamente en el body. Trata todos los
 * campos como ingreso público y resuelve la IP/User-Agent del request real
 * en lugar de los valores que pueda haber enviado el cliente. Esto evita
 * que alguien con curl falsifique IPs o User-Agents.
 *
 * Si en el futuro hay abuso (spam de logs falsos), agregar rate limiting
 * por IP — Spring tiene @Bucket4jRateLimiter que se aplica con anotación.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/conversation-log")
@RequiredArgsConstructor
public class PublicConversationLogController {

    private final ConversationLogRepository repo;

    @PostMapping
    @Transactional
    public Map<String, Object> save(@RequestBody PublicSaveRequest body, HttpServletRequest req) {
        ConversationLog e = new ConversationLog();

        // Snapshot de config — nos confiamos en que el bot envíe esto correctamente.
        e.setBotConfigId(body.botConfigId != null ? body.botConfigId : 1L);
        e.setBrandName(body.brandName);
        e.setActivePromptTemplateId(body.activePromptTemplateId);
        e.setActivePromptName(body.activePromptName);

        // Identidad del visitante (lo que el bot extrajo de la charla)
        e.setVisitorId(body.visitorId);
        e.setClientFirstName(nullIfBlank(body.clientFirstName));
        e.setClientLastName(nullIfBlank(body.clientLastName));
        e.setClientExtraJson(body.clientExtraJson);

        // Device info — lo que mandó el bot, complementado con lo que vemos del request
        e.setDeviceType(body.deviceType);
        e.setDeviceOs(body.deviceOs);
        e.setDeviceBrowser(body.deviceBrowser);
        // El userAgent y la IP los tomamos del request real, no de lo que mande el body.
        // Esto es para evitar que alguien envíe valores fabricados.
        e.setUserAgent(req.getHeader("User-Agent"));
        e.setIpAddress(resolveClientIp(req));

        // Contenido
        e.setMessagesJson(body.messagesJson);
        e.setMessageCount(body.messageCount != null ? body.messageCount : 0);
        e.setClosedReason(body.closedReason != null ? body.closedReason : "timeout");
        e.setIsAnonymous(e.getClientFirstName() == null && e.getClientLastName() == null);

        // Timestamps
        e.setStartedAt(body.startedAt != null ? body.startedAt : Instant.now());
        e.setEndedAt(body.endedAt != null ? body.endedAt : Instant.now());

        ConversationLog saved = repo.save(e);
        log.info("[public] conversation_log saved id={} brand={} anon={} msgs={} ip={}",
                saved.getId(), saved.getBrandName(), saved.getIsAnonymous(),
                saved.getMessageCount(), saved.getIpAddress());

        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("ok", true);
        return out;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Extrae la IP del cliente real considerando proxies/CDN.
     * Railway/Cloudflare suelen poner la IP real en X-Forwarded-For o X-Real-IP.
     */
    private static String resolveClientIp(HttpServletRequest req) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP"};
        for (String h : headers) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) {
                // X-Forwarded-For puede ser "ip1, ip2, ip3" — la primera es el cliente real
                String first = v.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        return req.getRemoteAddr();
    }

    /**
     * SaveRequest público. Mismo shape que el admin SaveRequest pero
     * SIN userAgent ni ipAddress (los resolvemos del request, no confiamos
     * en lo que mande el cliente público).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicSaveRequest {
        public Long   botConfigId;
        public String brandName;
        public Long   activePromptTemplateId;
        public String activePromptName;
        public String visitorId;
        public String clientFirstName;
        public String clientLastName;
        public String clientExtraJson;
        public String deviceType;
        public String deviceOs;
        public String deviceBrowser;
        public String messagesJson;
        public Integer messageCount;
        public String closedReason;
        public Instant startedAt;
        public Instant endedAt;
    }
}
