package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupFerryService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupFerryServiceRepository extends JpaRepository<GroupFerryService, Long> {

    Optional<GroupFerryService> findByMenuItemId(Long menuItemId);
    void deleteByMenuItemId(Long menuItemId);

}
