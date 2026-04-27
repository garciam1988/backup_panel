package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotTableRepository extends JpaRepository<BotTable, Long> {
    List<BotTable> findAllByOrderByNameAsc();
    List<BotTable> findByActiveTrueOrderByNameAsc();
    Optional<BotTable> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
