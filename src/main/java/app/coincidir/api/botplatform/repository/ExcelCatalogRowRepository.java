package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ExcelCatalogRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExcelCatalogRowRepository extends JpaRepository<ExcelCatalogRow, Long> {

    List<ExcelCatalogRow> findByCatalogIdOrderBySheetNameAscRowIndexAsc(Long catalogId);

    List<ExcelCatalogRow> findByCatalogIdAndSheetNameOrderByRowIndexAsc(Long catalogId, String sheetName);

    long countByCatalogId(Long catalogId);

    @Modifying
    @Query("DELETE FROM ExcelCatalogRow r WHERE r.catalogId = :catalogId")
    void deleteByCatalogId(@Param("catalogId") Long catalogId);
}
