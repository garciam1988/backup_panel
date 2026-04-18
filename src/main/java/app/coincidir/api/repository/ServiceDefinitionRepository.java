package app.coincidir.api.repository;

import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.ServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceDefinitionRepository extends JpaRepository<ServiceDefinition, Long> {

    Optional<ServiceDefinition> findByCode(ServiceCode code);

    List<ServiceDefinition> findByActiveTrueOrderByNameAsc();
}
