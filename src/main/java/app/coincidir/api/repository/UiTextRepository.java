package app.coincidir.api.repository;

import app.coincidir.api.domain.UiText;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UiTextRepository extends JpaRepository<UiText, Long> {
    Optional<UiText> findByKey(String key);
    boolean existsByKey(String key);
    List<UiText> findAllByOrderByCategoryAscKeyAsc();
}
