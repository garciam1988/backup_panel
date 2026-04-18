package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupAirService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupAirServiceRepository extends JpaRepository<GroupAirService, Long> {

    Optional<GroupAirService> findByMenuItemId(Long menuItemId);
    void deleteByMenuItemId(Long menuItemId);

}
