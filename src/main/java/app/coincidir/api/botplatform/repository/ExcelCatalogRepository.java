package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExcelCatalogRepository extends JpaRepository<ExcelCatalog, Long> {
    List<ExcelCatalog> findByActiveTrueOrderByNameAsc();
    List<ExcelCatalog> findAllByOrderByNameAsc();

    /**
     * Lookup legacy "por nombre global" — ya NO ES único después de Bloque 1
     * (la constraint pasó a ser (name, branch_id)). Puede devolver cualquiera
     * de los catálogos que comparten ese nombre entre sucursales distintas,
     * así que NO se debe usar para upsert. Se mantiene solo para callers viejos
     * que asumen "1 catálogo por nombre" (típicamente connectors a fuentes
     * únicas como un BotConfig global).
     *
     * Para upsert correcto usar {@link #findByNameAndBranchId(String, Long)}.
     */
    Optional<ExcelCatalog> findByName(String name);

    /**
     * Lookup para upsert. Acepta branchId=null para "buscar el catálogo global
     * con este nombre". Es el método que el upload debe usar para decidir si
     * crea un catálogo nuevo o reescribe uno existente.
     */
    @Query("SELECT c FROM ExcelCatalog c WHERE c.name = :name " +
            "AND ((:branchId IS NULL AND c.branchId IS NULL) OR c.branchId = :branchId)")
    Optional<ExcelCatalog> findByNameAndBranchId(@Param("name") String name,
                                                 @Param("branchId") Long branchId);

    // ─── Listados filtrados por branch ──────────────────────────────────────
    // Convención: "visibles para un user con scope X" = catálogos de X +
    // catálogos globales (branch_id IS NULL). Esto cubre tanto al gerente
    // como a DIOS con sucursal elegida.

    /**
     * Listado para users con scope a una branch específica. Trae los de esa
     * branch + los globales.
     */
    @Query("SELECT c FROM ExcelCatalog c " +
            "WHERE c.branchId = :branchId OR c.branchId IS NULL " +
            "ORDER BY c.name ASC")
    List<ExcelCatalog> findVisibleForBranchOrderByNameAsc(@Param("branchId") Long branchId);

    /**
     * Listado para users multi-branch (típicamente gerentes con varias sucursales
     * asignadas pero no DIOS). Trae los de cualquiera de esas branches +
     * los globales. Si la lista de branches es vacía, devuelve solo globales.
     */
    @Query("SELECT c FROM ExcelCatalog c " +
            "WHERE c.branchId IN :branchIds OR c.branchId IS NULL " +
            "ORDER BY c.name ASC")
    List<ExcelCatalog> findVisibleForBranchesOrderByNameAsc(@Param("branchIds") Collection<Long> branchIds);

    /**
     * Mismo que el anterior pero solo activos. Se usa cuando el caller pidió
     * {@code ?all=false} en el listado.
     */
    @Query("SELECT c FROM ExcelCatalog c " +
            "WHERE (c.branchId IN :branchIds OR c.branchId IS NULL) AND c.active = true " +
            "ORDER BY c.name ASC")
    List<ExcelCatalog> findActiveVisibleForBranchesOrderByNameAsc(@Param("branchIds") Collection<Long> branchIds);

    /**
     * Solo los globales (branch_id IS NULL). Se usa cuando un user no-DIOS
     * no tiene branches asignadas — solo puede ver lo global.
     */
    @Query("SELECT c FROM ExcelCatalog c WHERE c.branchId IS NULL ORDER BY c.name ASC")
    List<ExcelCatalog> findGlobalsOrderByNameAsc();

    @Query("SELECT c FROM ExcelCatalog c WHERE c.branchId IS NULL AND c.active = true ORDER BY c.name ASC")
    List<ExcelCatalog> findActiveGlobalsOrderByNameAsc();
}
