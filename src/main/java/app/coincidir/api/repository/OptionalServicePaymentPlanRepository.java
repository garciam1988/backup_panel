package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.OptionalServicePaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OptionalServicePaymentPlanRepository extends JpaRepository<OptionalServicePaymentPlan, Long> {
    Optional<OptionalServicePaymentPlan> findByMenuItemId(Long menuItemId);
}
