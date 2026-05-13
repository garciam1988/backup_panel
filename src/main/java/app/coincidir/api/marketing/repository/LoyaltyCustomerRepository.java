package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoyaltyCustomerRepository extends JpaRepository<LoyaltyCustomer, Long> {

    Optional<LoyaltyCustomer> findByCustomerHash(String customerHash);

    Optional<LoyaltyCustomer> findByPhone(String phone);

    Optional<LoyaltyCustomer> findByPhoneAndDeletedAtIsNull(String phone);

    Page<LoyaltyCustomer> findByDeletedAtIsNullOrderByEnrolledAtDesc(Pageable pageable);

    @Query("""
        SELECT c FROM LoyaltyCustomer c
        WHERE c.deletedAt IS NULL
          AND (
            LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
            OR c.phone LIKE CONCAT('%', :q, '%')
          )
        ORDER BY c.enrolledAt DESC
    """)
    Page<LoyaltyCustomer> search(@Param("q") String q, Pageable pageable);

    /** Clientes que cumplen años en los próximos N días (incluyendo hoy). */
    @Query(value = """
        SELECT * FROM loyalty_customer
        WHERE deleted_at IS NULL
          AND birth_date IS NOT NULL
          AND active = 1
          AND DATEDIFF(
                DATE_ADD(birth_date,
                  INTERVAL YEAR(CURDATE())-YEAR(birth_date)
                           + IF(DAYOFYEAR(birth_date) < DAYOFYEAR(CURDATE()), 1, 0)
                  YEAR),
                CURDATE()
              ) BETWEEN 0 AND :days
        """, nativeQuery = true)
    List<LoyaltyCustomer> findBirthdaysWithinNextDays(@Param("days") int days);

    @Query("""
        SELECT c FROM LoyaltyCustomer c
        WHERE c.deletedAt IS NULL
          AND c.active = true
          AND (c.lastActivityAt IS NULL OR c.lastActivityAt < :cutoff)
    """)
    List<LoyaltyCustomer> findInactiveSince(@Param("cutoff") Instant cutoff);

    long countByDeletedAtIsNullAndActiveTrue();
}
