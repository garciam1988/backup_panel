package app.coincidir.api.apiusage.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * UsageBudget — configuración global de alerta de presupuesto. Una sola
 * fila (singleton). El admin puede setear umbrales diario y/o mensual; el
 * dashboard muestra cartel rojo cuando se excede.
 *
 * Por ahora es global (no por provider). Si en el futuro se quiere alertar
 * por provider, se puede ampliar el modelo.
 */
@Entity
@Table(name = "usage_budget")
@Data
public class UsageBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "daily_limit_usd", precision = 10, scale = 2)
    private BigDecimal dailyLimitUsd;

    @Column(name = "monthly_limit_usd", precision = 10, scale = 2)
    private BigDecimal monthlyLimitUsd;

    @Column(name = "alerts_enabled", nullable = false)
    private Boolean alertsEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() { updatedAt = Instant.now(); }
}
