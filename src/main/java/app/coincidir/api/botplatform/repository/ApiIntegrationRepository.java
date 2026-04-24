package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ApiIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiIntegrationRepository extends JpaRepository<ApiIntegration, Long> {
    List<ApiIntegration> findAllByOrderByNameAsc();
    List<ApiIntegration> findByActiveTrueOrderByNameAsc();
    Optional<ApiIntegration> findByName(String name);
}
