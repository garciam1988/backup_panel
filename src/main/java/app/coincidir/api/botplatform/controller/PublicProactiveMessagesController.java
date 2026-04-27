package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ProactiveMessageQueue;
import app.coincidir.api.botplatform.repository.ProactiveMessageQueueRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PublicProactiveMessagesController — endpoint público (sin auth) que el
 * frontend del bot consume cada 30s vía polling para recibir mensajes
 * proactivos pendientes para su sesión.
 *
 * El job ProactiveRuleService los encola en proactive_message_queue. Acá
 * los devolvemos y los marcamos como entregados para no devolverlos dos
 * veces.
 *
 * Si no hay mensajes, devuelve lista vacía. El polling es de bajo costo
 * (solo una query por sessionId con índice).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/proactive-messages")
@RequiredArgsConstructor
public class PublicProactiveMessagesController {

    private final ProactiveMessageQueueRepository queueRepo;

    @GetMapping
    @Transactional
    public ProactiveMessagesResponse fetch(@RequestParam String sessionId) {
        ProactiveMessagesResponse resp = new ProactiveMessagesResponse();
        resp.messages = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return resp;

        List<ProactiveMessageQueue> pending =
                queueRepo.findBySessionIdAndDeliveredAtIsNullOrderByCreatedAtAsc(sessionId);
        if (pending.isEmpty()) return resp;

        Instant now = Instant.now();
        for (ProactiveMessageQueue m : pending) {
            ProactiveMessageDto d = new ProactiveMessageDto();
            d.id = m.getId();
            d.message = m.getMessage();
            d.createdAt = m.getCreatedAt();
            resp.messages.add(d);
            // Marcar como entregado de inmediato
            m.setDeliveredAt(now);
        }
        queueRepo.saveAll(pending);
        return resp;
    }

    // ─────── DTOs ───────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProactiveMessagesResponse {
        public List<ProactiveMessageDto> messages;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProactiveMessageDto {
        public Long id;
        public String message;
        public Instant createdAt;
    }
}
