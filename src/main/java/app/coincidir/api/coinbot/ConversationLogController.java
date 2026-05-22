package app.coincidir.api.coinbot;

import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.ConversationLogRepository;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConversationLogController — persistencia y consulta del log de conversaciones del CoinBot.
 *
 * Endpoints (todos bajo /api/admin/**, requieren JWT):
 *   POST /api/admin/conversation-log          → guarda una conversación cerrada (lo invoca el bot)
 *   GET  /api/admin/conversation-log          → listado paginado con filtros (para el AdminPanel)
 *   GET  /api/admin/conversation-log/brands   → distintas brand_name registradas (para filtro)
 *   GET  /api/admin/conversation-log/{id}     → detalle con transcript completo
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/conversation-log")
@RequiredArgsConstructor
public class ConversationLogController {

    private final ConversationLogRepository repo;

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/conversation-log — guardar cierre de conversación
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public Map<String, Object> save(@RequestBody SaveRequest body, HttpServletRequest req) {
        ConversationLog e = new ConversationLog();
        e.setBotConfigId(body.botConfigId != null ? body.botConfigId : 1L);
        e.setBrandName(body.brandName);
        e.setActivePromptTemplateId(body.activePromptTemplateId);
        e.setActivePromptName(body.activePromptName);
        e.setVisitorId(body.visitorId);
        e.setClientFirstName(nullIfBlank(body.clientFirstName));
        e.setClientLastName(nullIfBlank(body.clientLastName));
        e.setClientExtraJson(body.clientExtraJson);
        e.setDeviceType(body.deviceType);
        e.setDeviceOs(body.deviceOs);
        e.setDeviceBrowser(body.deviceBrowser);
        e.setUserAgent(body.userAgent != null ? body.userAgent : req.getHeader("User-Agent"));
        e.setIpAddress(resolveClientIp(req));
        e.setMessagesJson(body.messagesJson);
        e.setMessageCount(body.messageCount != null ? body.messageCount : 0);
        e.setClosedReason(body.closedReason != null ? body.closedReason : "timeout");
        e.setIsAnonymous(e.getClientFirstName() == null && e.getClientLastName() == null);
        e.setStartedAt(body.startedAt != null ? body.startedAt : Instant.now());
        e.setEndedAt(body.endedAt != null ? body.endedAt : Instant.now());

        // Tenancy: la sucursal viene PRIORITARIAMENTE del BranchContext del
        // request (header X-Branch-Id, manejado por BranchResolverFilter).
        // Como FALLBACK, leemos del payload (caso sendBeacon/keepalive que
        // no pueden mandar headers custom). Si ninguno tiene valor, queda
        // null (conversación "anónima de sucursal") y queda fuera de los
        // listados filtrados por sucursal.
        BranchScope scope = BranchContext.current();
        if (scope != null) {
            e.setBranchId(scope.getBranchId());
        } else if (body.branchId != null) {
            e.setBranchId(body.branchId);
        }

        ConversationLog saved = repo.save(e);
        log.info("conversation_log saved id={} brand={} branch={} anon={} msgs={}",
                saved.getId(), saved.getBrandName(), saved.getBranchId(),
                saved.getIsAnonymous(), saved.getMessageCount());

        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("ok", true);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/conversation-log?brand=&clientName=&freeText=&page=&size=
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@RequestParam(required = false) String brand,
                                    @RequestParam(required = false) String clientName,
                                    @RequestParam(required = false) String freeText,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));

        // Tenancy auto-filtro: si el request tiene branch en contexto (DIOS
        // eligió sucursal, o user no-DIOS que viene scopeado por su preferida),
        // filtramos por esa branch. Si no hay branch (DIOS no eligió → modo
        // marca), pasamos null y vemos todas las conversaciones.
        BranchScope scope = BranchContext.current();
        Long branchFilter = (scope != null) ? scope.getBranchId() : null;

        Page<ConversationLog> p = repo.search(
                nullIfBlank(brand),
                nullIfBlank(clientName),
                nullIfBlank(freeText),
                branchFilter,
                pageable);

        Map<String, Object> out = new HashMap<>();
        out.put("items", p.getContent().stream().map(ConversationLogDto::summaryFromEntity).toList());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        // Útil para que el frontend muestre un cartel "Mostrando: [Sucursal]" o "Todas".
        out.put("appliedBranchId", branchFilter);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/conversation-log/brands — para poblar el dropdown en /admin
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/brands")
    @Transactional(readOnly = true)
    public List<String> brands() {
        return repo.findDistinctBrandNames();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/conversation-log/{id} — detalle completo (con messages_json)
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ConversationLogDto detail(@org.springframework.web.bind.annotation.PathVariable Long id) {
        ConversationLog e = repo.findById(id).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

        // Tenancy: si hay branch en el contexto y la conversación pertenece a
        // OTRA branch, devolvemos 404 — no exponemos data cross-branch ni con
        // ID hardcodeado. Si la conversación tiene branchId=NULL (legacy) o
        // si no hay branch en contexto (DIOS sin elegir, modo marca),
        // dejamos pasar.
        BranchScope scope = BranchContext.current();
        if (scope != null && e.getBranchId() != null
                && !scope.getBranchId().equals(e.getBranchId())) {
            log.warn("[ConvLog] User en branch={} intentó leer conv id={} de branch={} — bloqueado",
                    scope.getBranchId(), id, e.getBranchId());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return ConversationLogDto.fullFromEntity(e);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private static String nullIfBlank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // primer IP de la lista
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String xri = req.getHeader("X-Real-Ip");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return req.getRemoteAddr();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public Long botConfigId;
        public String brandName;
        public Long activePromptTemplateId;
        public String activePromptName;
        public String visitorId;
        public String clientFirstName;
        public String clientLastName;
        public String clientExtraJson;
        public String deviceType;
        public String deviceOs;
        public String deviceBrowser;
        public String userAgent;
        /**
         * Sucursal de la conversación. Fallback: si el frontend pudo mandar
         * X-Branch-Id en el header, ese gana via BranchContext. Pero si no
         * (caso típico: cierre por sendBeacon que no permite headers
         * custom), el cliente lo manda en el payload.
         */
        public Long branchId;
        public String messagesJson;
        public Integer messageCount;
        public String closedReason;
        public Instant startedAt;
        public Instant endedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConversationLogDto {
        public Long id;
        public String brandName;
        public Long activePromptTemplateId;
        public String activePromptName;
        public String visitorId;
        public String clientFirstName;
        public String clientLastName;
        public String clientExtraJson;
        public String deviceType;
        public String deviceOs;
        public String deviceBrowser;
        public String userAgent;
        public String ipAddress;
        public String messagesJson;
        public Integer messageCount;
        public String closedReason;
        public Boolean isAnonymous;
        public Boolean hadReservation;
        public Instant startedAt;
        public Instant endedAt;

        /** DTO liviano para el listado — sin messagesJson ni userAgent. */
        public static ConversationLogDto summaryFromEntity(ConversationLog e) {
            ConversationLogDto d = new ConversationLogDto();
            d.id                     = e.getId();
            d.brandName              = e.getBrandName();
            d.activePromptTemplateId = e.getActivePromptTemplateId();
            d.activePromptName       = e.getActivePromptName();
            d.visitorId              = e.getVisitorId();
            d.clientFirstName        = e.getClientFirstName();
            d.clientLastName         = e.getClientLastName();
            d.deviceType             = e.getDeviceType();
            d.deviceOs               = e.getDeviceOs();
            d.deviceBrowser          = e.getDeviceBrowser();
            d.messageCount           = e.getMessageCount();
            d.closedReason           = e.getClosedReason();
            d.isAnonymous            = e.getIsAnonymous();
            d.hadReservation         = e.getHadReservation();
            d.startedAt              = e.getStartedAt();
            d.endedAt                = e.getEndedAt();
            return d;
        }

        /** DTO completo — para el endpoint de detalle. */
        public static ConversationLogDto fullFromEntity(ConversationLog e) {
            ConversationLogDto d = summaryFromEntity(e);
            d.clientExtraJson = e.getClientExtraJson();
            d.userAgent       = e.getUserAgent();
            d.ipAddress       = e.getIpAddress();
            d.messagesJson    = e.getMessagesJson();
            return d;
        }
    }
}
