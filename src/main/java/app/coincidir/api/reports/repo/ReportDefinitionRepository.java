package app.coincidir.api.reports.repo;

import app.coincidir.api.reports.model.ReportDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportDefinitionRepository extends JpaRepository<ReportDefinition, String> {
    List<ReportDefinition> findByPanelIgnoreCaseOrderByCategoryAscNameAsc(String panel);
    List<ReportDefinition> findByPanelIgnoreCaseAndTemplateOrderByCategoryAscNameAsc(String panel, boolean template);
    List<ReportDefinition> findByTemplateOrderByPanelAscCategoryAscNameAsc(boolean template);
}
