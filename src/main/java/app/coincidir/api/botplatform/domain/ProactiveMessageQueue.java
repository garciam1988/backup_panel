package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ProactiveMessageQueue — cola de mensajes proactivos pendientes de
 * entrega al frontend. El job los encola; el frontend los consume vía
 * polling cada 30s en /api/public/proactive-messages?sessionId=X.
 *
 * Cuando el frontend lee un mensaje, lo marcamos como deliveredAt para
 * no devolverlo dos veces.
 */
@Entity
@Table(name = "proactive_message_queue",
       indexes = {
           @Index(name = "idx_pmq_session_pending", columnList = "session_id, delivered_at")
       })
@Data
public class ProactiveMessageQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 120, nullable = false)
    private String sessionId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** NULL hasta que el frontend lo lee. Sirve también para limpieza
     *  posterior de mensajes viejos (un cron periódico podría borrar
     *  mensajes con deliveredAt antigua). */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
