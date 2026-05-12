package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ConnectorTableDescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectorTableDescriptionRepository
        extends JpaRepository<ConnectorTableDescription, Long> {

    /** Todas las descripciones de un conector, ordenadas alfabéticamente. */
    List<ConnectorTableDescription> findByConnectorIdOrderByTableNameAsc(Long connectorId);

    /** Lookup por (connectorId, tableName). El tableName se busca tal cual viene. */
    Optional<ConnectorTableDescription> findByConnectorIdAndTableName(
            Long connectorId, String tableName);

    /**
     * Borrado masivo de todas las descripciones de un conector. Útil para
     * limpiar al eliminar un conector — opcional. No lo usamos hoy
     * automáticamente pero queda disponible para mantenimiento.
     */
    @Modifying
    @Query("DELETE FROM ConnectorTableDescription d WHERE d.connectorId = :connectorId")
    void deleteAllByConnectorId(@Param("connectorId") Long connectorId);
}
