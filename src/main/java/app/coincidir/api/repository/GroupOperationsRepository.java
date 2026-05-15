package app.coincidir.api.repository;

import app.coincidir.api.domain.operations.GroupOperations;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupOperationsRepository extends JpaRepository<GroupOperations, Long> {
    Optional<GroupOperations> findByGroupId(Long groupId);

    List<GroupOperations> findAllByGroupIdIn(Collection<Long> groupIds);
}
