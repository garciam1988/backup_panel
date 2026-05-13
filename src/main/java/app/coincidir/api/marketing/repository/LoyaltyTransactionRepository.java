package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    Page<LoyaltyTransaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    List<LoyaltyTransaction> findTop20ByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Para reconstruir balances ante discrepancia. */
    @Query("""
        SELECT t FROM LoyaltyTransaction t
        WHERE t.customerId = :customerId
        ORDER BY t.createdAt ASC, t.id ASC
    """)
    List<LoyaltyTransaction> findAllByCustomerOrdered(@Param("customerId") Long customerId);

    long countByCreatedAtBetween(Instant from, Instant to);

    long countByTransactionTypeAndCreatedAtBetween(String type, Instant from, Instant to);
}
