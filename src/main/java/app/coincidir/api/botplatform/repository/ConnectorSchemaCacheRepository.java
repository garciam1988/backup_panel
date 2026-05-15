package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ConnectorSchemaCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ConnectorSchemaCacheRepository extends JpaRepository<ConnectorSchemaCache, Long> {

    Optional<ConnectorSchemaCache> findByConnectorId(Long connectorId);

    @Modifying
    @Query("DELETE FROM ConnectorSchemaCache c WHERE c.connectorId = :connectorId")
    void deleteByConnectorId(Long connectorId);
}
