package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * FraudAlert — Registro de un intento de fraude detectado por IA en una charla del bot.
 *
 * Se crea una fila cada vez que la IA clasifica un mensaje del cliente como
 * intento de fraude / manipulación. Incluye snapshot de quién era el cliente,
 * el mensaje sospechoso, el motivo detectado y el contexto de la conversación.
 */
@Entity
@Table(name = "fraud_alert", indexes = {
        @Index(name = "idx_fraud_created_at", columnList = "created_at"),
        @Index(name = "idx_fraud_brand_name", columnList = "brand_name"),
        @Index(name = "idx_fraud_resolved",   columnList = "resolved")
})
@Getter @Setter
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bot_config_id")
    private Long botConfigId;

    @Column(name = "brand_name", length = 150)
    private String brandName;

    // ── Identificación del cliente (si se pudo extraer) ───────────────────
    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    @Column(name = "client_first_name", length = 120)
    private String clientFirstName;

    @Column(name = "client_last_name", length = 120)
    private String clientLastName;

    @Column(name = "client_extra_json", columnDefinition = "TEXT")
    private String clientExtraJson;

    // ── Detalles del intento ──────────────────────────────────────────────
    /** El mensaje textual del cliente que disparó la detección. */
    @Column(name = "suspicious_message", columnDefinition = "TEXT")
    private String suspiciousMessage;

    /** Motivo/descripción que dio la IA (ej: "intento de modificar precios"). */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** Nivel de severidad asignado por IA: low | medium | high */
    @Column(name = "severity", length = 20)
    private String severity;

    /** JSON array del historial de mensajes hasta el momento de la detección. */
    @Column(name = "conversation_json", columnDefinition = "LONGTEXT")
    private String conversationJson;

    // ── Device info ──────────────────────────────────────────────────────
    @Column(name = "device_type", length = 30)
    private String deviceType;

    @Column(name = "device_os", length = 60)
    private String deviceOs;

    @Column(name = "device_browser", length = 60)
    private String deviceBrowser;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // ── Estado ───────────────────────────────────────────────────────────
    /** true si el admin marcó como revisado (desde /admin). */
    @Column(name = "resolved", nullable = false)
    private Boolean resolved = Boolean.FALSE;

    /** Nota libre del admin cuando resuelve. */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    /** true si se disparó el email con éxito. false si falló o está deshabilitado. */
    @Column(name = "email_sent", nullable = false)
    private Boolean emailSent = Boolean.FALSE;

    @Column(name = "email_error", length = 500)
    private String emailError;

    // ── Timestamps ───────────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (resolved == null) resolved = Boolean.FALSE;
        if (emailSent == null) emailSent = Boolean.FALSE;
    }
}
