package app.coincidir.api.repository;

import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.operations.OperationStatusCode;
import app.coincidir.api.domain.operations.ServiceOperationStatusDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceOperationStatusDefinitionRepository extends JpaRepository<ServiceOperationStatusDefinition, Long> {

    List<ServiceOperationStatusDefinition> findByServiceCodeAndActiveTrueOrderBySortOrderAsc(ServiceCode serviceCode);

    List<ServiceOperationStatusDefinition> findByActiveTrueOrderByServiceCodeAscSortOrderAsc();

    Optional<ServiceOperationStatusDefinition> findByServiceCodeAndStatusCode(ServiceCode serviceCode, OperationStatusCode statusCode);
}
