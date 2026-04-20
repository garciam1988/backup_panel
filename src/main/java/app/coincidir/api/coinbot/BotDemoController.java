package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotDemo;
import app.coincidir.api.repository.BotDemoRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * BotDemoController — gestión de demos (plantillas guardadas de config completa del bot).
 *
 * El backend acá es "dumb storage": persiste los JSON blobs que le manda el
 * frontend (config + catálogos + conectores + tools) y los devuelve cuando se
 * piden. La lógica de aplicar una demo vive en el frontend, que se encarga de
 * distribuir cada parte (config → BotConfig, catálogos → ExcelCatalogs, etc).
 *
 * Endpoints (todos bajo JWT):
 *   GET    /api/admin/bot-demo                 → listar demos (sin payload pesado)
 *   GET    /api/admin/bot-demo/{id}            → traer demo completa
 *   POST   /api/admin/bot-demo                 → crear nueva demo
 *   PUT    /api/admin/bot-demo/{id}            → sobrescribir demo existente
 *   PUT    /api/admin/bot-demo/{id}/mark-applied → marcar lastAppliedAt
 *   DELETE /api/admin/bot-demo/{id}            → borrar demo
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-demo")
@RequiredArgsConstructor
public class BotDemoController {

    private final BotDemoRepository repo;

    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public List<BotDemoSummary> list() {
        return repo.findAllByOrderByNameAsc().stream().map(BotDemoSummary::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public BotDemoFull detail(@PathVariable Long id) {
        BotDemo e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        return BotDemoFull.from(e);
    }

    // ─────────────────────────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public BotDemoSummary create(@RequestBody SaveRequest body, Authentication auth) {
        if (body.name == null || body.name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        String trimmed = body.name.trim();
        if (repo.existsByName(trimmed))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una demo con ese nombre");
        if (body.configJson == null || body.configJson.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configJson es obligatorio");

        BotDemo e = new BotDemo();
        e.setName(trimmed);
        e.setDescription(body.description);
        e.setConfigJson(body.configJson);
        e.setCatalogsJson(body.catalogsJson);
        e.setConnectorsJson(body.connectorsJson);
        e.setToolsJson(body.toolsJson);
        if (auth != null && auth.getName() != null) {
            e.setCreatedBy(auth.getName());
            e.setUpdatedBy(auth.getName());
        }
        BotDemo saved = repo.save(e);
        log.info("demo created id={} name={}", saved.getId(), saved.getName());
        return BotDemoSummary.from(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public BotDemoSummary update(@PathVariable Long id, @RequestBody SaveRequest body, Authentication auth) {
        BotDemo e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body.name != null && !body.name.isBlank()) {
            String trimmed = body.name.trim();
            if (!trimmed.equalsIgnoreCase(e.getName()) && repo.existsByName(trimmed))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una demo con ese nombre");
            e.setName(trimmed);
        }
        if (body.description != null)   e.setDescription(body.description);
        if (body.configJson != null)    e.setConfigJson(body.configJson);
        if (body.catalogsJson != null)  e.setCatalogsJson(body.catalogsJson);
        if (body.connectorsJson != null) e.setConnectorsJson(body.connectorsJson);
        if (body.toolsJson != null)     e.setToolsJson(body.toolsJson);
        if (auth != null && auth.getName() != null) e.setUpdatedBy(auth.getName());
        log.info("demo updated id={} name={}", id, e.getName());
        return BotDemoSummary.from(repo.save(e));
    }

    @PutMapping("/{id}/mark-applied")
    @Transactional
    public BotDemoSummary markApplied(@PathVariable Long id) {
        BotDemo e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.setLastAppliedAt(Instant.now());
        return BotDemoSummary.from(repo.save(e));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        repo.deleteById(id);
        log.info("demo deleted id={}", id);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public String name;
        public String description;
        public String configJson;
        public String catalogsJson;
        public String connectorsJson;
        public String toolsJson;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotDemoSummary {
        public Long    id;
        public String  name;
        public String  description;
        public Instant lastAppliedAt;
        public Instant createdAt;
        public String  createdBy;
        public Instant updatedAt;
        public String  updatedBy;
        public Boolean hasCatalogs;
        public Boolean hasConnectors;
        public Boolean hasTools;

        public static BotDemoSummary from(BotDemo e) {
            BotDemoSummary d = new BotDemoSummary();
            d.id            = e.getId();
            d.name          = e.getName();
            d.description   = e.getDescription();
            d.lastAppliedAt = e.getLastAppliedAt();
            d.createdAt     = e.getCreatedAt();
            d.createdBy     = e.getCreatedBy();
            d.updatedAt     = e.getUpdatedAt();
            d.updatedBy     = e.getUpdatedBy();
            d.hasCatalogs   = e.getCatalogsJson()   != null && !e.getCatalogsJson().isBlank()   && !e.getCatalogsJson().equals("[]");
            d.hasConnectors = e.getConnectorsJson() != null && !e.getConnectorsJson().isBlank() && !e.getConnectorsJson().equals("[]");
            d.hasTools      = e.getToolsJson()      != null && !e.getToolsJson().isBlank()      && !e.getToolsJson().equals("[]");
            return d;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotDemoFull extends BotDemoSummary {
        public String configJson;
        public String catalogsJson;
        public String connectorsJson;
        public String toolsJson;

        public static BotDemoFull from(BotDemo e) {
            BotDemoFull d = new BotDemoFull();
            d.id             = e.getId();
            d.name           = e.getName();
            d.description    = e.getDescription();
            d.lastAppliedAt  = e.getLastAppliedAt();
            d.createdAt      = e.getCreatedAt();
            d.createdBy      = e.getCreatedBy();
            d.updatedAt      = e.getUpdatedAt();
            d.updatedBy      = e.getUpdatedBy();
            d.configJson     = e.getConfigJson();
            d.catalogsJson   = e.getCatalogsJson();
            d.connectorsJson = e.getConnectorsJson();
            d.toolsJson      = e.getToolsJson();
            d.hasCatalogs    = e.getCatalogsJson()   != null && !e.getCatalogsJson().isBlank()   && !e.getCatalogsJson().equals("[]");
            d.hasConnectors  = e.getConnectorsJson() != null && !e.getConnectorsJson().isBlank() && !e.getConnectorsJson().equals("[]");
            d.hasTools       = e.getToolsJson()      != null && !e.getToolsJson().isBlank()      && !e.getToolsJson().equals("[]");
            return d;
        }
    }
}
