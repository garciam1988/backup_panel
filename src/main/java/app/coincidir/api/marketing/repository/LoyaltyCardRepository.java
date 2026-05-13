package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyCardRepository extends JpaRepository<LoyaltyCard, Long> {

    Optional<LoyaltyCard> findByCustomerId(Long customerId);
}
