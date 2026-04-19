package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotToolRepository extends JpaRepository<BotTool, Long> {
    List<BotTool> findByActiveTrueOrderByNameAsc();
    List<BotTool> findAllByOrderByNameAsc();
    Optional<BotTool> findByName(String name);
    List<BotTool> findByConnectorId(Long connectorId);
}
