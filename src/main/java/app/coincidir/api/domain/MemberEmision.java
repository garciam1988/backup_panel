package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Registro histórico de emisión / alta manual de pasajero desde Admin.
 */
@Entity
@Table(
        name = "member_emision",
        indexes = {
                @Index(name = "idx_member_emision_request_id", columnList = "request_id"),
                @Index(name = "idx_member_emision_created_at", columnList = "created_at"),
                @Index(name = "idx_member_emision_emitted", columnList = "emitted")
        }
)
@Getter
@Setter
public class MemberEmision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Referencia a la solicitud creada (si existe)
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "destination")
    private String destination;

    @Column(name = "travel_month", length = 100)
    private String travelMonth;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "age")
    private Integer age;

    @Column(name = "companion_type", length = 50)
    private String companionType;

    @Column(name = "gender", length = 50)
    private String gender;

    @Column(name = "quoted_value", precision = 12, scale = 4)
    private BigDecimal quotedValue;

    @Column(name = "quoted_at")
    private Instant quotedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Estado textual para mostrar en "Emisiones pendientes".
     * Ejemplos: PENDIENTE, EN_PROCESO, OBSERVADO, EMITIDO.
     */
    @Column(name = "status", length = 50)
    private String status;

    /**
     * Indica si ya se completó la emisión de aéreos para esta alta manual.
     * Se usa para mostrar / ocultar en la lista de "Emisiones pendientes".
     */
    @Column(name = "emitted")
    private Boolean emitted;

    @Column(name = "emitted_at")
    private Instant emittedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (emitted == null) emitted = false;
        if (status == null || status.isBlank()) status = "PENDIENTE";
    }
}
