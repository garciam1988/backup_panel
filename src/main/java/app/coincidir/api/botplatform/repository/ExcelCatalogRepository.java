package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExcelCatalogRepository extends JpaRepository<ExcelCatalog, Long> {
    List<ExcelCatalog> findByActiveTrueOrderByNameAsc();
    List<ExcelCatalog> findAllByOrderByNameAsc();
    Optional<ExcelCatalog> findByName(String name);
}
