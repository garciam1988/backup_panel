package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * BotOrder — pedido creado por el bot cuando el cliente confirma.
 *
 * El bot detecta por IA que el cliente completó el pedido y llama al endpoint
 * /api/bot/orders con el detalle. Este registro queda listo para que un
 * operador lo procese desde /panel.
 */
@Entity
@Table(name = "bot_order", indexes = {
        @Index(name = "idx_bot_order_status",     columnList = "status"),
        @Index(name = "idx_bot_order_created_at", columnList = "created_at"),
        @Index(name = "idx_bot_order_number",     columnList = "order_number")
})
@Getter @Setter
public class BotOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nº visible al cliente y al operador (incremental por día o global). */
    @Column(name = "order_number", length = 30, nullable = false, unique = true)
    private String orderNumber;

    // ── Marca / contexto ─────────────────────────────────────────────────
    @Column(name = "brand_name", length = 150)
    private String brandName;

    /** Referencia al ConversationLog (para poder mostrar el chat en el detalle). */
    @Column(name = "conversation_log_id")
    private Long conversationLogId;

    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    // ── Cliente ──────────────────────────────────────────────────────────
    @Column(name = "client_name", length = 200)
    private String clientName;

    @Column(name = "client_phone", length = 40)
    private String clientPhone;

    @Column(name = "client_email", length = 200)
    private String clientEmail;

    /** Dirección cuando es delivery (null si es retirar en local). */
    @Column(name = "client_address", length = 500)
    private String clientAddress;

    /** "delivery" | "pickup" | null si no aplica. */
    @Column(name = "fulfillment_type", length = 30)
    private String fulfillmentType;

    // ── Items y totales ──────────────────────────────────────────────────
    /** Items del pedido como JSON: [{name, quantity, unitPrice, subtotal, notes?}, ...]. */
    @Column(name = "items_json", columnDefinition = "LONGTEXT", nullable = false)
    private String itemsJson;

    /** Total calculado. */
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 10)
    private String currency;

    /** Forma de pago declarada por el cliente (efectivo, transferencia, etc). */
    @Column(name = "payment_method", length = 80)
    private String paymentMethod;

    /** Notas generales del cliente sobre el pedido completo. */
    @Column(name = "customer_notes", columnDefinition = "TEXT")
    private String customerNotes;

    // ── Estado / workflow ────────────────────────────────────────────────
    /** NEW | CONFIRMED | IN_PREPARATION | READY_FOR_PICKUP | DELIVERED | CANCELLED */
    @Column(name = "status", length = 30, nullable = false)
    private String status = "NEW";

    /** Historial de cambios de estado como JSON array. */
    @Column(name = "status_history_json", columnDefinition = "TEXT")
    private String statusHistoryJson;

    /** Nota interna del operador (no la ve el cliente). */
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    /** Si se canceló, motivo. */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // ── Timestamps / auditoría ───────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Usuario del panel que modificó por última vez. */
    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "NEW";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
