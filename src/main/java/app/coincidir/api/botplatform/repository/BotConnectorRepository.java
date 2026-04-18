package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotConnectorRepository extends JpaRepository<BotConnector, Long> {
    List<BotConnector> findByActiveTrueOrderByNameAsc();
    List<BotConnector> findAllByOrderByNameAsc();
    Optional<BotConnector> findByName(String name);
}
