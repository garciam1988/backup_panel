package app.coincidir.api.repository;

import app.coincidir.api.domain.PanelUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PanelUserRepository extends JpaRepository<PanelUser, Long> {
    Optional<PanelUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<PanelUser> findAllByOrderByUsernameAsc();
}
