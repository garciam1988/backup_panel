package app.coincidir.api.repository;

import app.coincidir.api.domain.AiOperationSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiOperationSuggestionRepository extends JpaRepository<AiOperationSuggestion, Long> {

    List<AiOperationSuggestion> findByDismissedFalseOrderByLastRunAtDesc();

    Optional<AiOperationSuggestion> findByGroupId(Long groupId);
}
