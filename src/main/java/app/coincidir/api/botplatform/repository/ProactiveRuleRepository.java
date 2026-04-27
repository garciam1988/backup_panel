package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ProactiveRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProactiveRuleRepository extends JpaRepository<ProactiveRule, Long> {
    List<ProactiveRule> findByTableIdOrderByIdAsc(Long tableId);
    List<ProactiveRule> findByActiveTrue();
}
