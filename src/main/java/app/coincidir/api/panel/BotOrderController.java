package app.coincidir.api.panel;

import app.coincidir.api.domain.BotOrder;
import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.repository.BotOrderRepository;
import app.coincidir.api.repository.ConversationLogRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * BotOrderController (/panel) — endpoints que consume el frontend de /panel.
 * Requiere JWT válido (cualquier rol, después filtramos por enabled_panels).
 */
@Slf4j
@RestController
@RequestMapping("/api/panel/orders")
@RequiredArgsConstructor
public class BotOrderController {

    private final BotOrderRepository repo;
    private final ConversationLogRepository convRepo;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String brand,
                                    @RequestParam(required = false) String freeText,
                                    @RequestParam(defaultValue = "today") String range,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "100") int size) {
        Instant since = rangeToInstant(range);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        Page<BotOrder> p = repo.search(
                emptyToNull(status),
                emptyToNull(brand),
                since,
                emptyToNull(freeText),
                pageable);
        Map<String, Object> out = new HashMap<>();
        out.put("items", p.getContent().stream().map(BotOrderDto::summaryFromEntity).toList());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        return out;
    }

    /** Widgets del dashboard: contadores por estado + total del día. */
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats(@RequestParam(defaultValue = "today") String range) {
        Instant since = rangeToInstant(range);
        Map<String, Long> byStatus = new HashMap<>();
        for (Object[] row : repo.countByStatus(since)) {
            byStatus.put((String) row[0], ((Number) row[1]).longValue());
        }
        BigDecimal total = repo.sumTotal(since);
        Map<String, Object> out = new HashMap<>();
        out.put("byStatus", byStatus);
        out.put("totalAmount", total);
        out.put("range", range);
        return out;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public BotOrderDto detail(@PathVariable Long id) {
        BotOrder o = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        BotOrderDto dto = BotOrderDto.fullFromEntity(o);
        // Adjuntar transcript del chat si hay conversación ligada
        if (o.getConversationLogId() != null) {
            ConversationLog conv = convRepo.findById(o.getConversationLogId()).orElse(null);
            if (conv != null) dto.conversationMessagesJson = conv.getMessagesJson();
        }
        return dto;
    }

    @PutMapping("/{id}/status")
    @Transactional
    public BotOrderDto changeStatus(@PathVariable Long id,
                                    @RequestBody ChangeStatusRequest body,
                                    Authentication auth) {
        BotOrder o = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body == null || body.status == null || body.status.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta status");

        String oldStatus = o.getStatus();
        String newStatus = body.status.trim().toUpperCase();
        o.setStatus(newStatus);
        if ("CANCELLED".equals(newStatus) && body.reason != null) {
            o.setCancellationReason(body.reason);
        }
        if (auth != null && auth.getName() != null) o.setUpdatedBy(auth.getName());
        // Append al historial
        try {
            List<Map<String, Object>> history;
            if (o.getStatusHistoryJson() != null && !o.getStatusHistoryJson().isBlank()) {
                history = objectMapper.readValue(o.getStatusHistoryJson(), new TypeReference<>() {});
            } else {
                history = new ArrayList<>();
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("from", oldStatus);
            entry.put("to", newStatus);
            entry.put("at", Instant.now().toString());
            entry.put("by", auth != null ? auth.getName() : null);
            if (body.reason != null) entry.put("reason", body.reason);
            history.add(entry);
            o.setStatusHistoryJson(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("status history append failed: {}", e.getMessage());
        }
        return BotOrderDto.fullFromEntity(repo.save(o));
    }

    @PutMapping("/{id}")
    @Transactional
    public BotOrderDto update(@PathVariable Long id,
                              @RequestBody UpdateRequest body,
                              Authentication auth) {
        BotOrder o = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body.clientName != null)     o.setClientName(body.clientName);
        if (body.clientPhone != null)    o.setClientPhone(body.clientPhone);
        if (body.clientEmail != null)    o.setClientEmail(body.clientEmail);
        if (body.clientAddress != null)  o.setClientAddress(body.clientAddress);
        if (body.fulfillmentType != null) o.setFulfillmentType(body.fulfillmentType);
        if (body.itemsJson != null)      o.setItemsJson(body.itemsJson);
        if (body.totalAmount != null)    o.setTotalAmount(body.totalAmount);
        if (body.paymentMethod != null)  o.setPaymentMethod(body.paymentMethod);
        if (body.customerNotes != null)  o.setCustomerNotes(body.customerNotes);
        if (body.internalNotes != null)  o.setInternalNotes(body.internalNotes);
        if (auth != null && auth.getName() != null) o.setUpdatedBy(auth.getName());
        return BotOrderDto.fullFromEntity(repo.save(o));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private static Instant rangeToInstant(String range) {
        if (range == null) return null;
        ZoneId tz = ZoneId.of("America/Argentina/Buenos_Aires");
        LocalDate today = LocalDate.now(tz);
        switch (range) {
            case "today":    return today.atStartOfDay(tz).toInstant();
            case "yesterday":return today.minusDays(1).atStartOfDay(tz).toInstant();
            case "week":     return today.minusDays(7).atStartOfDay(tz).toInstant();
            case "month":    return today.minusDays(30).atStartOfDay(tz).toInstant();
            case "all":      return null;
            default:         return today.atStartOfDay(tz).toInstant();
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChangeStatusRequest {
        public String status;
        public String reason;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateRequest {
        public String     clientName;
        public String     clientPhone;
        public String     clientEmail;
        public String     clientAddress;
        public String     fulfillmentType;
        public String     itemsJson;
        public BigDecimal totalAmount;
        public String     paymentMethod;
        public String     customerNotes;
        public String     internalNotes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotOrderDto {
        public Long       id;
        public String     orderNumber;
        public String     brandName;
        public Long       conversationLogId;
        public String     visitorId;
        public String     clientName;
        public String     clientPhone;
        public String     clientEmail;
        public String     clientAddress;
        public String     fulfillmentType;
        public String     itemsJson;
        public BigDecimal totalAmount;
        public String     currency;
        public String     paymentMethod;
        public String     customerNotes;
        public String     status;
        public String     statusHistoryJson;
        public String     internalNotes;
        public String     cancellationReason;
        public Instant    createdAt;
        public Instant    updatedAt;
        public String     updatedBy;
        /** Solo viene cuando se pide detalle. */
        public String     conversationMessagesJson;

        public static BotOrderDto summaryFromEntity(BotOrder o) {
            BotOrderDto d = new BotOrderDto();
            d.id             = o.getId();
            d.orderNumber    = o.getOrderNumber();
            d.brandName      = o.getBrandName();
            d.clientName     = o.getClientName();
            d.clientPhone    = o.getClientPhone();
            d.fulfillmentType = o.getFulfillmentType();
            d.itemsJson      = o.getItemsJson();
            d.totalAmount    = o.getTotalAmount();
            d.currency       = o.getCurrency();
            d.status         = o.getStatus();
            d.createdAt      = o.getCreatedAt();
            return d;
        }

        public static BotOrderDto fullFromEntity(BotOrder o) {
            BotOrderDto d = summaryFromEntity(o);
            d.conversationLogId = o.getConversationLogId();
            d.visitorId         = o.getVisitorId();
            d.clientEmail       = o.getClientEmail();
            d.clientAddress     = o.getClientAddress();
            d.paymentMethod     = o.getPaymentMethod();
            d.customerNotes     = o.getCustomerNotes();
            d.statusHistoryJson = o.getStatusHistoryJson();
            d.internalNotes     = o.getInternalNotes();
            d.cancellationReason = o.getCancellationReason();
            d.updatedAt         = o.getUpdatedAt();
            d.updatedBy         = o.getUpdatedBy();
            return d;
        }
    }
}
