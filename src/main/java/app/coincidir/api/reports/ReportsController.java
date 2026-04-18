package app.coincidir.api.reports;

import app.coincidir.api.reports.dto.*;
import app.coincidir.api.reports.model.ReportDefinition;
import app.coincidir.api.reports.service.ReportDefinitionService;
import app.coincidir.api.reports.service.ReportQueryService;
import app.coincidir.api.reports.service.ReportRegistry;
import app.coincidir.api.reports.service.ReportDataSource;
import app.coincidir.api.reports.service.ReportField;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportsController {

    private final ReportRegistry registry;
    private final ReportQueryService queryService;
    private final ReportDefinitionService definitionService;

    @GetMapping("/schema")
    public ReportSchemaResponse schema(@RequestParam(required = false) String panel) {
        List<ReportDataSourceDto> dss = registry.getDataSources().stream()
                .map(this::map)
                .toList();
        return new ReportSchemaResponse(panel == null ? "" : panel, dss);
    }

    @PostMapping("/query")
    public ReportQueryResponse query(@RequestBody ReportQueryRequest body) {
        return queryService.query(body);
    }

    @GetMapping("/definitions")
    public List<ReportDefinition> listDefinitions(
            @RequestParam(required = false) String panel,
            @RequestParam(required = false) Boolean templates
    ) {
        return definitionService.list(panel, templates);
    }

    @PostMapping("/definitions")
    public ReportDefinition createDefinition(@RequestBody ReportDefinition body) {
        return definitionService.create(body);
    }

    @PutMapping("/definitions/{id}")
    public ReportDefinition updateDefinition(@PathVariable String id, @RequestBody ReportDefinition body) {
        return definitionService.update(id, body);
    }

    @DeleteMapping("/definitions/{id}")
    public void deleteDefinition(@PathVariable String id) {
        definitionService.delete(id);
    }

    private ReportDataSourceDto map(ReportDataSource ds) {
        List<ReportFieldDto> fields = ds.fields().stream().map(this::map).toList();
        return new ReportDataSourceDto(ds.id(), ds.label(), ds.description(), fields);
    }

    private ReportFieldDto map(ReportField f) {
        List<String> aggs = f.allowedAggs().stream().map(Enum::name).toList();
        return new ReportFieldDto(f.key(), f.label(), f.type().name(), aggs);
    }
}
