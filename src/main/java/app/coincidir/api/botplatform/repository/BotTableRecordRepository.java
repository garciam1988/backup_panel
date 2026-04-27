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

    Page<BotTableRecord> findByTableIdOrderByCreatedAtDesc(Long tableId, Pageable pageable);

    List<BotTableRecord> findByTableIdOrderByCreatedAtDesc(Long tableId);

    long countByTableId(Long tableId);

    @Modifying
    @Query("DELETE FROM BotTableRecord r WHERE r.tableId = :tableId")
    int deleteAllByTableId(@Param("tableId") Long tableId);
}
