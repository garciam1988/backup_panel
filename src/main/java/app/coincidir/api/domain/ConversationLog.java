package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * ConversationLog — Registro de una conversación completa del CoinBot con un cliente.
 *
 * Se guarda UNA fila por conversación cuando se detecta cierre (inactividad o cierre explícito).
 * El transcript vive en messages_json como array JSON.
 */
@Entity
@Table(name = "conversation_log", indexes = {
        @Index(name = "idx_conv_started_at", columnList = "started_at"),
        @Index(name = "idx_conv_brand_name", columnList = "brand_name"),
        @Index(name = "idx_conv_client_names", columnList = "client_first_name,client_last_name")
})
@Getter @Setter
public class ConversationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID del singleton bot_config en el momento de la charla (por si en el futuro hay multi-bot). */
    @Column(name = "bot_config_id")
    private Long botConfigId;

    // ── Snapshot de config al momento del cierre ───────────────────────────
    /** brandName vigente al cerrar la charla (snapshot — el admin puede haberlo cambiado después). */
    @Column(name = "brand_name", length = 150)
    private String brandName;

    /** ID del BotPromptTemplate activo (si había uno). */
    @Column(name = "active_prompt_template_id")
    private Long activePromptTemplateId;

    /** Nombre del template activo (snapshot). */
    @Column(name = "active_prompt_name", length = 200)
    private String activePromptName;

    // ── Identificación del visitante ───────────────────────────────────────
    /** UUID generado en el frontend al abrir el bot. No persiste entre sesiones. */
    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    /** Nombre del cliente extraído de la charla (puede ser null si es anónimo). */
    @Column(name = "client_first_name", length = 120)
    private String clientFirstName;

    @Column(name = "client_last_name", length = 120)
    private String clientLastName;

    /** DNI, email, teléfono, etc. como JSON libre. */
    @Column(name = "client_extra_json", columnDefinition = "TEXT")
    private String clientExtraJson;

    // ── Device info ────────────────────────────────────────────────────────
    /** desktop, mobile, tablet */
    @Column(name = "device_type", length = 30)
    private String deviceType;

    /** Windows, macOS, Linux, Android, iOS, Other */
    @Column(name = "device_os", length = 60)
    private String deviceOs;

    /** Chrome, Safari, Firefox, Edge, Other */
    @Column(name = "device_browser", length = 60)
    private String deviceBrowser;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // ─── Geolocalización resuelta de la IP ────────────────────────
    // Llenadas por GeolocationService al guardar el log. Si la resolución
    // falla, quedan null. El frontend del módulo Clientes las usa para
    // mostrar país/provincia/ciudad.
    @Column(name = "geo_country", length = 80)
    private String geoCountry;

    @Column(name = "geo_country_code", length = 2)
    private String geoCountryCode;

    @Column(name = "geo_region", length = 100)
    private String geoRegion;

    @Column(name = "geo_city", length = 100)
    private String geoCity;

    // ── Contenido ──────────────────────────────────────────────────────────
    /** JSON array: [{ "role": "user"|"assistant", "content": "...", "ts": "...ISO..." }, ...] */
    @Column(name = "messages_json", columnDefinition = "LONGTEXT")
    private String messagesJson;

    @Column(name = "message_count")
    private Integer messageCount;

    /** tool_add_record | tool_update_record | tool_delete_record | timeout | beforeunload | manual | reservation_made | in_progress */
    @Column(name = "closed_reason", length = 40)
    private String closedReason;

    /**
     * Marca persistente: TRUE si en algún momento de la charla se ejecutó
     * exitosamente una tool de escritura (add_record/update_record/delete_record).
     * Sirve para que el motivo de cierre final (timeout / beforeunload) no
     * pise la trazabilidad de "esta charla terminó en reserva". Una vez TRUE,
     * el UPSERT NO baja a FALSE.
     */
    @Column(name = "had_reservation", nullable = false)
    private Boolean hadReservation = Boolean.FALSE;

    /** true si no se pudo extraer nombre/apellido. */
    @Column(name = "is_anonymous", nullable = false)
    private Boolean isAnonymous = Boolean.TRUE;

    // ── Timestamps ─────────────────────────────────────────────────────────
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (startedAt == null) startedAt = Instant.now();
        if (endedAt == null) endedAt = Instant.now();
        if (isAnonymous == null) isAnonymous = Boolean.TRUE;
        if (hadReservation == null) hadReservation = Boolean.FALSE;
    }
}
