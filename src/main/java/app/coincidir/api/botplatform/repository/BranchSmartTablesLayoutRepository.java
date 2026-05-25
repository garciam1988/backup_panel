package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BranchSmartTablesLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BranchSmartTablesLayoutRepository extends JpaRepository<BranchSmartTablesLayout, Long> {

    Optional<BranchSmartTablesLayout> findByBranchId(Long branchId);
}
