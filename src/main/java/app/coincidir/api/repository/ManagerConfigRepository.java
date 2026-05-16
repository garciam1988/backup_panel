package app.coincidir.api.repository;

import app.coincidir.api.domain.ManagerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repo del singleton {@code manager_config}.
 * Igual que {@link BotConfigRepository}: siempre se busca por ID=1.
 */
public interface ManagerConfigRepository extends JpaRepository<ManagerConfig, Long> {
}
