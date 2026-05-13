package app.coincidir.api.marketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * LoyaltyProgram — Configuración del programa de fidelización.
 *
 * Pensado como singleton en el MVP (siempre id=1), pero la tabla soporta
 * múltiples programas a futuro (ej: un programa "regular" + un programa
 * "premium" cross-brand). El servicio MarketingService.getActiveProgram()
 * devuelve el primero activo por ahora.
 *
 * Acá viven los tipos habilitados (stamps/puntos/cashback), las reglas de
 * cálculo, los métodos de identificación del cliente y el diseño visual
 * de la tarjeta PWA. NO viven acá las reglas de bonificación dinámicas
 * (esas van como BotTable "marketing_rules" para que el admin las pueda
 * editar libremente sin tocar este registro).
 */
@Entity
@Table(name = "loyalty_program")
@Getter @Setter
public class LoyaltyProgram {

    public enum MultiBranchMode {
        /** Stamps sirven en cualquier local, sin tracking de sucursal. */
        GLOBAL,
        /** Globales, pero registramos branch_id por movimiento (default). */
        GLOBAL_WITH_TRACKING,
        /** Cada local mantiene su propio programa separado. */
        PER_BRANCH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "stamps_enabled", nullable = false)
    private Boolean stampsEnabled = true;

    @Column(name = "points_enabled", nullable = false)
    private Boolean pointsEnabled = false;

    @Column(name = "cashback_enabled", nullable = false)
    private Boolean cashbackEnabled = false;

    /** Cantidad de stamps para canjear el premio principal (ej: 10). */
    @Column(name = "stamps_required")
    private Integer stampsRequired;

    /** Descripción del premio principal en lenguaje natural. */
    @Column(name = "stamps_reward_text", length = 255)
    private String stampsRewardText;

    /** Si true, al canjear se resetean los stamps a 0. Si false, se restan. */
    @Column(name = "stamps_reset_on_redeem", nullable = false)
    private Boolean stampsResetOnRedeem = true;

    /**
     * Factor de conversión: cuántos puntos por unidad de moneda.
     * Ej: 0.01 = 1 punto cada $100. Ej: 1.0 = 1 punto por $1.
     */
    @Column(name = "points_per_currency", precision = 10, scale = 4)
    private BigDecimal pointsPerCurrency;

    /** Días hasta que los puntos expiran. NULL = no expiran. */
    @Column(name = "points_expiry_days")
    private Integer pointsExpiryDays;

    /** Porcentaje de cashback (ej: 5.00 = 5% de cada compra). */
    @Column(name = "cashback_percentage", precision = 5, scale = 2)
    private BigDecimal cashbackPercentage;

    @Column(name = "cashback_min_purchase", precision = 12, scale = 2)
    private BigDecimal cashbackMinPurchase;

    @Column(name = "cashback_expiry_days")
    private Integer cashbackExpiryDays;

    /** Techo de cashback por compra (NULL = sin techo). */
    @Column(name = "cashback_max_per_purchase", precision = 12, scale = 2)
    private BigDecimal cashbackMaxPerPurchase;

    /**
     * JSON array con los métodos habilitados para identificar al cliente
     * cuando se le suman stamps en el local. Valores válidos:
     *   - "customer_qr": mozo escanea el QR de la PWA del cliente
     *   - "local_qr":    cliente escanea un QR del local
     *   - "phone":       mozo ingresa el teléfono del cliente
     *
     * Default: los tres. El admin puede deshabilitar los que no quiera.
     */
    @Column(name = "identification_methods", columnDefinition = "JSON", nullable = false)
    private String identificationMethods;

    @Enumerated(EnumType.STRING)
    @Column(name = "multi_branch_mode", nullable = false, length = 30)
    private MultiBranchMode multiBranchMode = MultiBranchMode.GLOBAL_WITH_TRACKING;

    /**
     * JSON con la config de diseño de la tarjeta digital del cliente.
     * Forma esperada:
     * {
     *   "primaryColor":"#E63946",
     *   "secondaryColor":"#1D3557",
     *   "logoUrl":"https://...",
     *   "backgroundUrl":"https://...",
     *   "stampIconUrl":"https://...",
     *   "showQrOnCard":true,
     *   "quickActions":["reserve","menu","rewards","promos"]
     * }
     */
    @Column(name = "card_design_json", columnDefinition = "JSON")
    private String cardDesignJson;

    /**
     * Si está en false, el programa queda pausado: no se suman ni se canjean
     * stamps/puntos/cashback, pero la PWA sigue funcionando en modo "leer
     * historial". Para apagar el módulo completo usar bot_config.marketing_enabled.
     */
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
    void onUpdate() { updatedAt = Instant.now(); }
}
