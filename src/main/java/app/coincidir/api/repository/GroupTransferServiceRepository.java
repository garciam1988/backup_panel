package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupTransferService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupTransferServiceRepository
        extends JpaRepository<GroupTransferService, Long> {

    Optional<GroupTransferService> findByMenuItemId(Long menuItemId);
    void deleteByMenuItemId(Long menuItemId);

}
