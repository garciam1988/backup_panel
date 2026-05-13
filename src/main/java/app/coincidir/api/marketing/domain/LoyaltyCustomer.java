package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * LoyaltyCustomer — Persona enrolada al programa de fidelización.
 *
 * Identidad propia del módulo Marketing. Se identifica externamente por
 * customerHash (no por id) para que las URLs públicas de la PWA
 * (loyalty.cliente.com/c/{hash}) no expongan IDs autoincrementales.
 *
 * Vínculo blando con reservas: si el cliente se enroló desde el flujo del
 * bot tras una reserva, reservationTableSlug + reservationRecordId apuntan
 * al BotTableRecord correspondiente. Si vino directo por QR sin haber
 * reservado, ambos quedan NULL — el módulo Marketing funciona igual.
 *
 * Deduplicación: clave única por phone. Si el bot intenta enrolar a un número
 * que ya existe, el servicio reactiva el registro y actualiza cualquier dato
 * nuevo (nombre, email, birthdate). NUNCA se duplica un cliente por teléfono.
 */
@Entity
@Table(name = "loyalty_customer")
@Getter @Setter
public class LoyaltyCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identificador opaco usado en URLs públicas de la PWA. Generado vía
     * nanoid (21 chars URL-safe). No se expone el id autoincremental por
     * privacidad y para evitar enumeración.
     */
    @Column(name = "customer_hash", nullable = false, length = 40, unique = true)
    private String customerHash;

    @Column(name = "phone", nullable = false, length = 20, unique = true)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** Slug de la BotTable de reservas (ej: "reservas"). NULL si no aplica. */
    @Column(name = "reservation_table_slug", length = 60)
    private String reservationTableSlug;

    /** ID del BotTableRecord que originó el enrolamiento. NULL si no aplica. */
    @Column(name = "reservation_record_id")
    private Long reservationRecordId;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    /** 'bot','panel','qr','manual','import' */
    @Column(name = "enrolled_source", length = 30)
    private String enrolledSource;

    @Column(name = "enrolled_branch", length = 64)
    private String enrolledBranch;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "total_visits", nullable = false)
    private Integer totalVisits = 0;

    @Column(name = "accepts_whatsapp", nullable = false)
    private Boolean acceptsWhatsapp = true;

    @Column(name = "accepts_email", nullable = false)
    private Boolean acceptsEmail = true;

    @Column(name = "accepts_push", nullable = false)
    private Boolean acceptsPush = true;

    /**
     * Subscription del navegador para Web Push (JSON con endpoint, keys.p256dh,
     * keys.auth). NULL si el cliente nunca se subscribió o si revocó permisos.
     * Lo escribe el frontend de la PWA después de pedir permiso al usuario.
     */
    @Column(name = "web_push_subscription", columnDefinition = "JSON")
    private String webPushSubscription;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (enrolledAt == null) enrolledAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
