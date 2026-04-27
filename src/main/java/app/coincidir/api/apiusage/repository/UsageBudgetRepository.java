package app.coincidir.api.apiusage.repository;

import app.coincidir.api.apiusage.domain.UsageBudget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageBudgetRepository extends JpaRepository<UsageBudget, Long> {
    // Singleton: usamos findFirstByOrderByIdAsc() y si no hay, creamos.
    UsageBudget findFirstByOrderByIdAsc();
}
