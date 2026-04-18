package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotPromptTemplate;
import app.coincidir.api.repository.BotPromptTemplateRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * BotPromptTemplateController — CRUD de plantillas de prompt por rubro.
 *
 * Endpoints (bajo /api/admin/, requieren JWT):
 *   GET    /api/admin/bot-prompt-templates          → listado (all=true incluye inactivos)
 *   GET    /api/admin/bot-prompt-templates/{id}     → uno
 *   POST   /api/admin/bot-prompt-templates          → crear
 *   PUT    /api/admin/bot-prompt-templates/{id}     → editar
 *   DELETE /api/admin/bot-prompt-templates/{id}     → borrar (hard delete)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-prompt-templates")
@RequiredArgsConstructor
public class BotPromptTemplateController {

    private final BotPromptTemplateRepository repo;

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/bot-prompt-templates?all=true
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public List<BotPromptTemplateDto> list(@RequestParam(value = "all", defaultValue = "false") boolean all) {
        List<BotPromptTemplate> list = all
                ? repo.findAllByOrderByNameAsc()
                : repo.findByActiveTrueOrderByNameAsc();
        return list.stream().map(BotPromptTemplateDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<BotPromptTemplateDto> getOne(@PathVariable Long id) {
        return repo.findById(id)
                .map(BotPromptTemplateDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/bot-prompt-templates
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public ResponseEntity<BotPromptTemplateDto> create(@RequestBody BotPromptTemplateDto dto) {
        if (dto.name == null || dto.name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<BotPromptTemplate> existing = repo.findByName(dto.name.trim());
        if (existing.isPresent()) {
            // Si ya existe con ese nombre, devolvemos 409 (Conflict)
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        BotPromptTemplate entity = new BotPromptTemplate();
        entity.setName(dto.name.trim());
        entity.setDescription(dto.description);
        entity.setPromptText(dto.promptText != null ? dto.promptText : "");
        entity.setActive(dto.active != null ? dto.active : Boolean.TRUE);
        BotPromptTemplate saved = repo.save(entity);
        log.info("bot_prompt_template creado: id={}, name='{}'", saved.getId(), saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(BotPromptTemplateDto.fromEntity(saved));
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUT /api/admin/bot-prompt-templates/{id}
    // ─────────────────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<BotPromptTemplateDto> update(@PathVariable Long id, @RequestBody BotPromptTemplateDto dto) {
        return repo.findById(id).map(entity -> {
            if (dto.name != null && !dto.name.isBlank()) entity.setName(dto.name.trim());
            if (dto.description != null) entity.setDescription(dto.description);
            if (dto.promptText != null)  entity.setPromptText(dto.promptText);
            if (dto.active != null)      entity.setActive(dto.active);
            BotPromptTemplate saved = repo.save(entity);
            log.info("bot_prompt_template actualizado: id={}, name='{}'", saved.getId(), saved.getName());
            return ResponseEntity.ok(BotPromptTemplateDto.fromEntity(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE /api/admin/bot-prompt-templates/{id}
    // ─────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        log.info("bot_prompt_template eliminado: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotPromptTemplateDto {
        public Long    id;
        public String  name;
        public String  description;
        public String  promptText;
        public Boolean active;
        public Instant updatedAt;

        public static BotPromptTemplateDto fromEntity(BotPromptTemplate e) {
            BotPromptTemplateDto d = new BotPromptTemplateDto();
            d.id          = e.getId();
            d.name        = e.getName();
            d.description = e.getDescription();
            d.promptText  = e.getPromptText();
            d.active      = e.getActive();
            d.updatedAt   = e.getUpdatedAt();
            return d;
        }
    }
}
