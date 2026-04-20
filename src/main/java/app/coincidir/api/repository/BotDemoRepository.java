package app.coincidir.api.repository;

import app.coincidir.api.domain.BotDemo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BotDemoRepository extends JpaRepository<BotDemo, Long> {
    List<BotDemo> findAllByOrderByNameAsc();
    Optional<BotDemo> findByName(String name);
    boolean existsByName(String name);
}
