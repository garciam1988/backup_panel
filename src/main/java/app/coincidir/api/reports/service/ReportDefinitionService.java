package app.coincidir.api.reports.service;

import app.coincidir.api.reports.model.ReportDefinition;
import app.coincidir.api.reports.repo.ReportDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ReportDefinitionService {

    private final ReportDefinitionRepository repo;

    public ReportDefinitionService(ReportDefinitionRepository repo) {
        this.repo = repo;
    }

    public List<ReportDefinition> list(String panel, Boolean templates) {
        String p = panel == null || panel.isBlank() ? null : panel.trim();
        boolean onlyTemplates = templates != null && templates;

        if (p != null) {
            if (templates == null) {
                return repo.findByPanelIgnoreCaseOrderByCategoryAscNameAsc(p);
            }
            return repo.findByPanelIgnoreCaseAndTemplateOrderByCategoryAscNameAsc(p, onlyTemplates);
        }

        if (templates == null) {
            return repo.findAll();
        }
        return repo.findByTemplateOrderByPanelAscCategoryAscNameAsc(onlyTemplates);
    }

    public ReportDefinition create(ReportDefinition body) {
        if (body == null) throw new app.coincidir.api.common.exception.BadRequestException("Body requerido");
        if (body.getDataSourceId() == null || body.getDataSourceId().isBlank()) {
            throw new app.coincidir.api.common.exception.BadRequestException("dataSourceId requerido");
        }
        if (body.getPanel() == null || body.getPanel().isBlank()) body.setPanel("ADMIN");
        if (body.getName() == null || body.getName().isBlank()) body.setName("Nuevo reporte");
        return repo.save(body);
    }

    public ReportDefinition update(String id, ReportDefinition body) {
        if (id == null || id.isBlank()) throw new app.coincidir.api.common.exception.BadRequestException("id requerido");
        ReportDefinition existing = repo.findById(id).orElseThrow(() -> new app.coincidir.api.common.exception.NotFoundException("Reporte no encontrado"));

        if (body != null) {
            if (body.getPanel() != null && !body.getPanel().isBlank()) existing.setPanel(body.getPanel());
            if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName());
            if (body.getCategory() != null) existing.setCategory(body.getCategory());
            if (body.getDataSourceId() != null && !body.getDataSourceId().isBlank()) existing.setDataSourceId(body.getDataSourceId());
            if (body.getConfigJson() != null) existing.setConfigJson(body.getConfigJson());
            existing.setTemplate(body.isTemplate());
            existing.setShared(body.isShared());
        }

        return repo.save(existing);
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) throw new app.coincidir.api.common.exception.BadRequestException("id requerido");
        repo.deleteById(id);
    }
}
