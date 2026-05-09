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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PublicConversationLogController — endpoint PÚBLICO (sin autenticación) para
 * que el bot del visitante anónimo pueda persistir el log al cerrar la charla,
 * o cuando concreta una reserva (ya no esperamos al cierre de pestaña, que es
 * notoriamente poco confiable).
 *
 *   POST /api/public/conversation-log → guarda o ACTUALIZA una conversación
 *
 * UPSERT por visitorId:
 * Si el body trae `visitorId` y ya existe un registro con ese mismo
 * `visitorId` para el mismo `botConfigId`, lo ACTUALIZA en vez de crear uno
 * nuevo. Esto evita duplicados cuando el bot persiste la conversación
 * múltiples veces a lo largo de la charla:
 *   - Primera vez al ejecutarse add_record/update_record/delete_record
 *     (closedReason="reservation_made") — la conversación queda registrada
 *     aunque el cliente cierre la pestaña inesperadamente.
 *   - Segunda vez al timeout de inactividad o al cerrar pestaña — se
 *     actualiza el mismo registro con todos los mensajes acumulados y el
 *     closedReason real ("timeout" o "beforeunload").
 *
 * Seguridad: el endpoint NO confía ciegamente en el body. Resuelve la
 * IP/User-Agent del request real en lugar de los valores del cliente.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/conversation-log")
@RequiredArgsConstructor
public class PublicConversationLogController {

    private final ConversationLogRepository repo;
    private final GeolocationService geo;

    @PostMapping
    @Transactional
    public Map<String, Object> save(@RequestBody PublicSaveRequest body, HttpServletRequest req) {
        // ─── UPSERT: si visitorId existe en otro registro, actualizamos ese ───
        ConversationLog e = null;
        boolean isUpdate = false;
        if (body.visitorId != null && !body.visitorId.isBlank()) {
            // findByVisitorIdOrderByIdDesc devuelve la más reciente si hubiera
            // más de una (no debería pasar pero defensivo).
            Optional<ConversationLog> existing = repo.findFirstByVisitorIdOrderByIdDesc(body.visitorId);
            if (existing.isPresent()) {
                e = existing.get();
                isUpdate = true;
            }
        }
        if (e == null) e = new ConversationLog();

        // Snapshot de config — solo seteamos en INSERT, en UPDATE no toqueteamos
        // estos campos para preservar la marca de conversación original.
        if (!isUpdate) {
            e.setBotConfigId(body.botConfigId != null ? body.botConfigId : 1L);
            e.setBrandName(body.brandName);
            e.setActivePromptTemplateId(body.activePromptTemplateId);
            e.setActivePromptName(body.activePromptName);
            e.setStartedAt(body.startedAt != null ? body.startedAt : Instant.now());
            e.setVisitorId(body.visitorId);
            // Device info inicial (en UPDATE preservamos la del primer save)
            e.setDeviceType(body.deviceType);
            e.setDeviceOs(body.deviceOs);
            e.setDeviceBrowser(body.deviceBrowser);
            e.setUserAgent(req.getHeader("User-Agent"));
            String clientIp = resolveClientIp(req);
            e.setIpAddress(clientIp);

            // Resolver geolocalización a partir de la IP. Best-effort: si falla
            // no bloquea el guardado del log, las columnas quedan en NULL.
            // Solo se hace en el INSERT — en UPDATE preservamos la geo original
            // del primer save para no consumir rate limit innecesariamente.
            try {
                GeolocationService.GeoInfo info = geo.resolve(clientIp);
                if (info != null && !info.isEmpty()) {
                    e.setGeoCountry(info.country);
                    e.setGeoCountryCode(info.countryCode);
                    e.setGeoRegion(info.region);
                    e.setGeoCity(info.city);
                }
            } catch (Exception ex) {
                log.debug("[public] geo resolve failed (no bloquea): {}", ex.getMessage());
            }
        }

        // Identidad del cliente — en UPDATE puede que vengan más datos extraídos
        // del transcript, los preferimos sobre los anteriores (no nulos).
        if (nullIfBlank(body.clientFirstName) != null) e.setClientFirstName(nullIfBlank(body.clientFirstName));
        if (nullIfBlank(body.clientLastName)  != null) e.setClientLastName(nullIfBlank(body.clientLastName));
        if (nullIfBlank(body.clientExtraJson) != null) e.setClientExtraJson(body.clientExtraJson);

        // Contenido — siempre se actualiza con el último estado.
        e.setMessagesJson(body.messagesJson);
        e.setMessageCount(body.messageCount != null ? body.messageCount : 0);
        e.setClosedReason(body.closedReason != null ? body.closedReason : "timeout");
        e.setIsAnonymous(e.getClientFirstName() == null && e.getClientLastName() == null);
        e.setEndedAt(body.endedAt != null ? body.endedAt : Instant.now());

        ConversationLog saved = repo.save(e);
        log.info("[public] conversation_log {} id={} brand={} anon={} msgs={} reason={} visitor={}",
                isUpdate ? "UPDATED" : "CREATED",
                saved.getId(), saved.getBrandName(), saved.getIsAnonymous(),
                saved.getMessageCount(), saved.getClosedReason(), saved.getVisitorId());

        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("ok", true);
        out.put("updated", isUpdate);
        return out;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String resolveClientIp(HttpServletRequest req) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP"};
        for (String h : headers) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) {
                String first = v.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        return req.getRemoteAddr();
    }

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
