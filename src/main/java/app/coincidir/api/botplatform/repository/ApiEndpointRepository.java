package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, Long> {
    List<ApiEndpoint> findByIntegrationIdOrderByTagAscPathAsc(Long integrationId);
    List<ApiEndpoint> findByIntegrationIdAndActiveAsToolTrue(Long integrationId);
    List<ApiEndpoint> findByActiveAsToolTrue();
    Optional<ApiEndpoint> findByToolName(String toolName);
    void deleteByIntegrationId(Long integrationId);
}
