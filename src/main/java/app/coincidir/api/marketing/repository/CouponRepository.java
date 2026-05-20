package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    Page<Coupon> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Lista cupones NO archivados (archived_at IS NULL), orden desc por created_at. */
    Page<Coupon> findByArchivedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT c FROM Coupon c
        WHERE c.active = true
          AND c.archivedAt IS NULL
          AND (c.validFrom  IS NULL OR c.validFrom  <= :now)
          AND (c.validUntil IS NULL OR c.validUntil >= :now)
          AND (c.maxUsesTotal IS NULL OR c.currentUses < c.maxUsesTotal)
    """)
    List<Coupon> findActiveAt(@Param("now") Instant now);
}
