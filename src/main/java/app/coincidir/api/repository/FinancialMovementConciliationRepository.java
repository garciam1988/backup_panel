package app.coincidir.api.repository;

import app.coincidir.api.domain.conciliation.FinancialMovementConciliation;
import app.coincidir.api.domain.conciliation.FinancialMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FinancialMovementConciliationRepository extends JpaRepository<FinancialMovementConciliation, Long> {

    Optional<FinancialMovementConciliation> findByMovementTypeAndMovementId(FinancialMovementType movementType, Long movementId);

    @Query("""
            select c
              from FinancialMovementConciliation c
             where c.movementType = :type
               and c.movementId in :ids
            """)
    List<FinancialMovementConciliation> findAllByMovementTypeAndMovementIdIn(
            @Param("type") FinancialMovementType type,
            @Param("ids") Collection<Long> ids
    );
}
