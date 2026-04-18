package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.ServicePaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServicePaymentPlanRepository extends JpaRepository<ServicePaymentPlan, Long> {
    Optional<ServicePaymentPlan> findByMenuItemId(Long menuItemId);
}
