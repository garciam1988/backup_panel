package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BotConnectorRepository extends JpaRepository<BotConnector, Long> {
    List<BotConnector> findByActiveTrueOrderByNameAsc();
    List<BotConnector> findAllByOrderByNameAsc();

    /**
     * Lookup legacy "por nombre global" — después de Bloque 1 el unique pasó a
     * ser (name, branch_id), así que dos sucursales pueden tener un connector
     * llamado igual. Este método puede devolver cualquiera de los dos.
     * Usar {@link #findByNameAndBranchId(String, Long)} para upsert correcto.
     */
    Optional<BotConnector> findByName(String name);

    @Query("SELECT c FROM BotConnector c WHERE c.name = :name " +
            "AND ((:branchId IS NULL AND c.branchId IS NULL) OR c.branchId = :branchId)")
    Optional<BotConnector> findByNameAndBranchId(@Param("name") String name,
                                                 @Param("branchId") Long branchId);

    @Query("SELECT c FROM BotConnector c " +
            "WHERE c.branchId IN :branchIds OR c.branchId IS NULL " +
            "ORDER BY c.name ASC")
    List<BotConnector> findVisibleForBranchesOrderByNameAsc(@Param("branchIds") Collection<Long> branchIds);

    @Query("SELECT c FROM BotConnector c " +
            "WHERE (c.branchId IN :branchIds OR c.branchId IS NULL) AND c.active = true " +
            "ORDER BY c.name ASC")
    List<BotConnector> findActiveVisibleForBranchesOrderByNameAsc(@Param("branchIds") Collection<Long> branchIds);

    @Query("SELECT c FROM BotConnector c WHERE c.branchId IS NULL ORDER BY c.name ASC")
    List<BotConnector> findGlobalsOrderByNameAsc();

    @Query("SELECT c FROM BotConnector c WHERE c.branchId IS NULL AND c.active = true ORDER BY c.name ASC")
    List<BotConnector> findActiveGlobalsOrderByNameAsc();
}
