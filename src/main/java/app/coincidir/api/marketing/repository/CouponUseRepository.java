package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.CouponUse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponUseRepository extends JpaRepository<CouponUse, Long> {

    List<CouponUse> findByCouponIdOrderByUsedAtDesc(Long couponId);

    List<CouponUse> findByCustomerIdOrderByUsedAtDesc(Long customerId);

    int countByCouponIdAndCustomerId(Long couponId, Long customerId);

    int countByCouponId(Long couponId);
}
