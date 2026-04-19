package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * TravelPackage — Paquete de viaje promocional editable desde /admin.
 *
 * Reemplaza el catalog.js hardcodeado (Fase 5B de la plataforma).
 * El bot consulta esta tabla vía tools configurables (consultar_paquetes,
 * cotizar_paquete).
 */
@Entity
@Table(name = "travel_package")
@Getter @Setter
public class TravelPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código del paquete (ej: "BAR-JUL-01"). Único, inmutable desde el punto de vista del negocio. */
    @Column(name = "codigo", nullable = false, length = 50, unique = true)
    private String codigo;

    /** Destino normalizado (ej: "bariloche", "mendoza"). En lowercase, sin acentos. */
    @Column(name = "destino", nullable = false, length = 50)
    private String destino;

    /** Nombre comercial del paquete. */
    @Column(name = "nombre", nullable = false, length = 200)
    private String nombre;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "fecha_salida")
    private LocalDate fechaSalida;

    @Column(name = "fecha_regreso")
    private LocalDate fechaRegreso;

    @Column(name = "noches")
    private Integer noches;

    @Column(name = "precio_adulto", precision = 12, scale = 2)
    private BigDecimal precioAdulto;

    @Column(name = "precio_menor", precision = 12, scale = 2)
    private BigDecimal precioMenor;

    @Column(name = "moneda", length = 3)
    private String moneda = "USD";

    @Column(name = "cupos_disponibles")
    private Integer cuposDisponibles;

    @Column(name = "incluye_vuelo", nullable = false)
    private Boolean incluyeVuelo = true;

    @Column(name = "incluye_hotel", nullable = false)
    private Boolean incluyeHotel = true;

    @Column(name = "incluye_traslados", nullable = false)
    private Boolean incluyeTraslados = true;

    @Column(name = "incluye_asistencia", nullable = false)
    private Boolean incluyeAsistencia = true;

    @Column(name = "incluye_ferry", nullable = false)
    private Boolean incluyeFerry = false;

    /** JSON array con qué NO está incluido (ej: ["Excursiones", "Comidas"]). */
    @Column(name = "no_incluye_json", columnDefinition = "TEXT")
    private String noIncluyeJson;

    /** Si está en false, no aparece para el bot. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
