package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupDestinationTransferService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupDestinationTransferServiceRepository
        extends JpaRepository<GroupDestinationTransferService, Long> {

    Optional<GroupDestinationTransferService> findByMenuItemId(Long menuItemId);
    void deleteByMenuItemId(Long menuItemId);

}
