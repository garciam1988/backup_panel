package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.LoyaltyProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, Long> {

    /** Devuelve el primer programa activo. En MVP siempre debería haber uno (id=1). */
    Optional<LoyaltyProgram> findFirstByActiveTrueOrderByIdAsc();
}
