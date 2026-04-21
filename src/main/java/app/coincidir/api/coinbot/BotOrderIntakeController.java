package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.domain.BotOrder;
import app.coincidir.api.repository.BotConfigRepository;
import app.coincidir.api.repository.BotOrderRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BotOrderIntakeController — endpoint que usa el bot para registrar un pedido.
 *
 * Vive bajo /api/admin/** (el bot usa el token del user "coinbot" para
 * autenticarse igual que ya hace con otros endpoints). Genera nº secuencial
 * "P-YYYYMMDD-NNN" y calcula total si no viene.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-order-intake")
@RequiredArgsConstructor
public class BotOrderIntakeController {

    private final BotOrderRepository repo;
    private final BotConfigRepository configRepo;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody IntakeRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body vacío");
        if (body.items == null || body.items.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta items");

        // Calcular total si no vino
        BigDecimal total = body.totalAmount;
        if (total == null) {
            total = BigDecimal.ZERO;
            for (IntakeItem it : body.items) {
                if (it.subtotal != null) {
                    total = total.add(it.subtotal);
                } else if (it.unitPrice != null && it.quantity != null) {
                    total = total.add(it.unitPrice.multiply(BigDecimal.valueOf(it.quantity)));
                }
            }
        }

        // Leer prefijo del order number desde la config
        BotConfig cfg = configRepo.findById(1L).orElse(null);
        String prefix = "P-";
        String currency = "ARS";
        try {
            if (cfg != null && cfg.getPanelOrdersConfigJson() != null) {
                Map<String, Object> pc = objectMapper.readValue(
                        cfg.getPanelOrdersConfigJson(), new TypeReference<>() {});
                if (pc.get("orderNumberPrefix") instanceof String s) prefix = s;
                if (pc.get("currency") instanceof String s2) currency = s2;
            }
        } catch (Exception ignored) {}

        String dateKey = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        BotOrder o = new BotOrder();
        // Nº: P-20260420-001 (se ajusta en retry si hay colisión)
        int attempt = 0;
        while (true) {
            attempt++;
            String suffix = String.format("%03d", (repo.maxId() == null ? 0 : repo.maxId()) + attempt);
            String candidate = prefix + dateKey + "-" + suffix;
            try {
                o.setOrderNumber(candidate);
                break;
            } catch (Exception e) {
                if (attempt > 10) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar order number");
            }
        }

        o.setBrandName(body.brandName);
        o.setConversationLogId(body.conversationLogId);
        o.setVisitorId(body.visitorId);
        o.setClientName(body.clientName);
        o.setClientPhone(body.clientPhone);
        o.setClientEmail(body.clientEmail);
        o.setClientAddress(body.clientAddress);
        o.setFulfillmentType(body.fulfillmentType);
        o.setPaymentMethod(body.paymentMethod);
        o.setCustomerNotes(body.customerNotes);
        o.setTotalAmount(total);
        o.setCurrency(body.currency != null ? body.currency : currency);
        o.setStatus("NEW");

        try {
            o.setItemsJson(objectMapper.writeValueAsString(body.items));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items inválidos: " + e.getMessage());
        }

        BotOrder saved = repo.save(o);
        log.info("bot_order created id={} number={} client={} total={}",
                saved.getId(), saved.getOrderNumber(), saved.getClientName(), saved.getTotalAmount());

        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("orderNumber", saved.getOrderNumber());
        out.put("total", saved.getTotalAmount());
        out.put("currency", saved.getCurrency());
        return out;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IntakeRequest {
        public String brandName;
        public Long   conversationLogId;
        public String visitorId;
        public String clientName;
        public String clientPhone;
        public String clientEmail;
        public String clientAddress;
        public String fulfillmentType;     // "delivery" | "pickup"
        public String paymentMethod;
        public String customerNotes;
        public BigDecimal totalAmount;
        public String currency;
        public List<IntakeItem> items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IntakeItem {
        public String     name;
        public Integer    quantity;
        public BigDecimal unitPrice;
        public BigDecimal subtotal;
        public String     notes;
    }
}
