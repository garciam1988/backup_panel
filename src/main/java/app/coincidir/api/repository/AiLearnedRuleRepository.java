package app.coincidir.api.repository;

import app.coincidir.api.domain.AiLearnedRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiLearnedRuleRepository extends JpaRepository<AiLearnedRule, Long> {
    List<AiLearnedRule> findAllByOrderByCreatedAtAsc();
}
