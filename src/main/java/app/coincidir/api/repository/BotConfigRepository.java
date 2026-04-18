package app.coincidir.api.repository;

import app.coincidir.api.domain.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {
    // No necesita métodos extra — es un singleton, se accede por findById(1L).
}
