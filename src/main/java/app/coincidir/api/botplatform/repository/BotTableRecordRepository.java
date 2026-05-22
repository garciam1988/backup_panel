package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotTableRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BotTableRecordRepository extends JpaRepository<BotTableRecord, Long> {

    // ── Métodos legacy (sin filtro por branch) ─────────────────────────
    //
    // Se mantienen porque los usan:
    //   - El admin con rol DIOS (que ve todas las branches según selección).
    //   - El listener de Marketing que sincroniza customers.
    //   - El job de recordatorios que itera todas las tablas.
    //
    // Para el BOT PÚBLICO y los listados de gerentes por-sucursal, usar las
    // variantes *ByBranch de abajo.

    Page<BotTableRecord> findByTableIdOrderByCreatedAtDesc(Long tableId, Pageable pageable);

    List<BotTableRecord> findByTableIdOrderByCreatedAtDesc(Long tableId);

    long countByTableId(Long tableId);

    @Modifying
    @Query("DELETE FROM BotTableRecord r WHERE r.tableId = :tableId")
    int deleteAllByTableId(@Param("tableId") Long tableId);

    // ── Variantes scoped por branch ────────────────────────────────────

    List<BotTableRecord> findByTableIdAndBranchIdOrderByCreatedAtDesc(
            Long tableId, Long branchId);

    Page<BotTableRecord> findByTableIdAndBranchIdOrderByCreatedAtDesc(
            Long tableId, Long branchId, Pageable pageable);

    long countByTableIdAndBranchId(Long tableId, Long branchId);

    /** Cuántos records existen en una branch (cross-tabla). Lo usa la
     *  pantalla de gestión de sucursales para advertir antes de borrar. */
    long countByBranchId(Long branchId);

    List<BotTableRecord> findByTableIdAndBranchIdInOrderByCreatedAtDesc(
            Long tableId, List<Long> branchIds);

    Page<BotTableRecord> findByTableIdAndBranchIdInOrderByCreatedAtDesc(
            Long tableId, List<Long> branchIds, Pageable pageable);

    // ── Backfill ───────────────────────────────────────────────────────

    List<BotTableRecord> findByBranchIdIsNull();

    @Modifying
    @Query("UPDATE BotTableRecord r SET r.branchId = :branchId WHERE r.branchId IS NULL")
    int assignDefaultBranchToLegacyRecords(@Param("branchId") Long branchId);

    long countByBranchIdIsNull();
}
