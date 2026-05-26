package app.coincidir.api.coinbot;

import app.coincidir.api.audit.service.AuditService;
import app.coincidir.api.domain.BotPromptTemplate;
import app.coincidir.api.domain.BotPromptTemplateVersion;
import app.coincidir.api.repository.BotPromptTemplateRepository;
import app.coincidir.api.repository.BotPromptTemplateVersionRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final BotPromptTemplateVersionRepository versionRepo;
    private final AuditService auditService;

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

        // Audit
        try {
            auditService.logCreate(
                "prompt.create",
                "PromptTemplate",
                String.valueOf(saved.getId()),
                saved.getName(),
                "admin",
                snapshotForAudit(saved)
            );
        } catch (Exception ignored) {}

        return ResponseEntity.status(HttpStatus.CREATED).body(BotPromptTemplateDto.fromEntity(saved));
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUT /api/admin/bot-prompt-templates/{id}
    // ─────────────────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<BotPromptTemplateDto> update(@PathVariable Long id, @RequestBody BotPromptTemplateDto dto) {
        return repo.findById(id).map(entity -> {
            // Snapshot ANTES del cambio para diff de audit
            Map<String, Object> oldSnap = snapshotForAudit(entity);

            // ── Versionado: guardar snapshot del state PREVIO ──────────────
            // Solo creamos versión si efectivamente algún campo "histórico"
            // (promptText/name/description) va a cambiar. Si solo se toggle
            // active (que no es contenido del prompt en sí), NO creamos
            // version — sería ruido en el timeline.
            //
            // Importante: el snapshot guarda el state PREVIO al cambio, no el
            // que se está aplicando. Así, si el operador hace update X→Y y
            // después quiere "volver a X", restaura la versión que guardamos
            // acá. La versión que muestra Y se crea con el PRÓXIMO update.
            boolean willChangeContent =
                    (dto.promptText != null && !dto.promptText.equals(entity.getPromptText())) ||
                    (dto.name != null && !dto.name.trim().isBlank() && !dto.name.trim().equals(entity.getName())) ||
                    (dto.description != null && !java.util.Objects.equals(dto.description, entity.getDescription()));

            if (willChangeContent) {
                createVersion(entity, dto.reason != null ? dto.reason : "edit");
            }

            if (dto.name != null && !dto.name.isBlank()) entity.setName(dto.name.trim());
            if (dto.description != null) entity.setDescription(dto.description);
            if (dto.promptText != null)  entity.setPromptText(dto.promptText);
            if (dto.active != null)      entity.setActive(dto.active);
            BotPromptTemplate saved = repo.save(entity);
            log.info("bot_prompt_template actualizado: id={}, name='{}'", saved.getId(), saved.getName());

            // Audit: si pasó de inactivo a activo, registramos también como
            // "prompt.activate" para que sea fácil filtrar quien activó qué
            // plantilla (que es la acción "peligrosa" — la activa la usa el bot).
            try {
                Map<String, Object> newSnap = snapshotForAudit(saved);
                Object oldActive = oldSnap.get("active");
                Object newActive = newSnap.get("active");
                if (Boolean.FALSE.equals(oldActive) && Boolean.TRUE.equals(newActive)) {
                    auditService.logAction(
                        "prompt.activate",
                        "PromptTemplate",
                        String.valueOf(saved.getId()),
                        saved.getName(),
                        "admin",
                        "Activó la plantilla \"" + saved.getName() + "\""
                    );
                }
                auditService.logUpdate(
                    "prompt.update",
                    "PromptTemplate",
                    String.valueOf(saved.getId()),
                    saved.getName(),
                    "admin",
                    oldSnap,
                    newSnap
                );
            } catch (Exception ignored) {}

            return ResponseEntity.ok(BotPromptTemplateDto.fromEntity(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE /api/admin/bot-prompt-templates/{id}
    // ─────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Optional<BotPromptTemplate> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        // Snapshot previo para audit
        BotPromptTemplate entity = opt.get();
        Map<String, Object> oldSnap = snapshotForAudit(entity);
        String label = entity.getName();

        // Borrar versiones antes que el template. Sin FK cascade necesitamos
        // ser explícitos. Si el template no tiene versiones (caso raro pero
        // posible), deleteByTemplateId devuelve 0 y no falla.
        int versionsRemoved = versionRepo.deleteByTemplateId(id);
        if (versionsRemoved > 0) {
            log.info("bot_prompt_template_version eliminadas: template_id={} count={}", id, versionsRemoved);
        }

        repo.deleteById(id);
        log.info("bot_prompt_template eliminado: id={}", id);

        try {
            auditService.logDelete(
                "prompt.delete",
                "PromptTemplate",
                String.valueOf(id),
                label,
                "admin",
                oldSnap
            );
        } catch (Exception ignored) {}

        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/bot-prompt-templates/{id}/versions
    // Listado de versiones del template, más nuevas primero. Devuelve metadata
    // (sin el promptText) para que el dropdown del historial cargue rápido
    // aunque haya muchas versiones largas.
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/versions")
    @Transactional(readOnly = true)
    public List<PromptVersionListItemDto> listVersions(@PathVariable Long id) {
        // Verificar que el template existe — sin esto, devolveríamos [] aunque
        // el id sea inválido, que es confuso para el frontend.
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template no encontrado");
        }
        List<BotPromptTemplateVersion> list = versionRepo.findByTemplateIdOrderByVersionNumberDesc(id);
        return list.stream().map(PromptVersionListItemDto::fromEntity).toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/admin/bot-prompt-templates/{id}/versions/{versionId}
    // Detalle de una versión específica (con el promptText completo).
    // ─────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/versions/{versionId}")
    @Transactional(readOnly = true)
    public PromptVersionDetailDto getVersion(@PathVariable Long id, @PathVariable Long versionId) {
        BotPromptTemplateVersion v = versionRepo.findByIdAndTemplateId(versionId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión no encontrada"));
        return PromptVersionDetailDto.fromEntity(v);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/admin/bot-prompt-templates/{id}/versions/{versionId}/restore
    // Restaura una versión vieja: copia su contenido al template actual.
    // ANTES de hacerlo, guarda el state actual como una nueva versión con
    // reason="restore" — así el operador puede "deshacer la restauración"
    // si se arrepiente.
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/versions/{versionId}/restore")
    @Transactional
    public ResponseEntity<BotPromptTemplateDto> restoreVersion(@PathVariable Long id,
                                                                @PathVariable Long versionId) {
        BotPromptTemplate template = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template no encontrado"));
        BotPromptTemplateVersion v = versionRepo.findByIdAndTemplateId(versionId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión no encontrada"));

        // 1) Snapshot del state ACTUAL antes de pisarlo (para que se pueda
        // deshacer la restauración).
        Map<String, Object> oldSnap = snapshotForAudit(template);
        createVersion(template, "restore");

        // 2) Pisar el template con el contenido de la versión vieja.
        // Restauramos name/description/promptText/active — todo lo que estaba
        // en esa versión. Si el operador no quiere, después puede editar el
        // name/description sin que eso afecte el promptText.
        template.setName(v.getName());
        template.setDescription(v.getDescription());
        template.setPromptText(v.getPromptText());
        template.setActive(v.getActive());
        BotPromptTemplate saved = repo.save(template);

        log.info("bot_prompt_template restaurado: id={} desde version_id={} (v#{})",
                id, versionId, v.getVersionNumber());

        // 3) Audit
        try {
            Map<String, Object> newSnap = snapshotForAudit(saved);
            auditService.logActionWithChanges(
                "prompt.restore",
                "PromptTemplate",
                String.valueOf(id),
                saved.getName(),
                "admin",
                "Restauró la plantilla a la versión #" + v.getVersionNumber(),
                oldSnap,
                newSnap
            );
        } catch (Exception e) {
            log.warn("[BotPromptTemplate] falló logActionWithChanges (restore): {}", e.getMessage());
        }

        return ResponseEntity.ok(BotPromptTemplateDto.fromEntity(saved));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Snapshot de los campos auditables de una plantilla. El promptText es
     * intencionalmente incluido — si alguien lo modificó queremos saberlo,
     * aunque el diff puede llegar a ser largo si el prompt es grande. El
     * AuditEventListener ya hace truncate del JSON serializado al guardar.
     */
    private Map<String, Object> snapshotForAudit(BotPromptTemplate e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) return m;
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("promptText", e.getPromptText());
        m.put("active", e.getActive());
        return m;
    }

    /**
     * Crea una entrada en {@code bot_prompt_template_version} con el state
     * ACTUAL del template (antes de aplicarle cambios). Se invoca desde
     * {@code update()} cuando hay cambios de contenido, y desde
     * {@code restoreVersion()} antes de pisar el template con la versión
     * vieja.
     *
     * Captura el username del operador desde el SecurityContext —  si no hay
     * (caller sin auth, raro acá porque /api/admin/** está gateado), queda
     * null y el frontend muestra "—".
     */
    private void createVersion(BotPromptTemplate entity, String reason) {
        try {
            BotPromptTemplateVersion v = new BotPromptTemplateVersion();
            v.setTemplateId(entity.getId());
            v.setVersionNumber(versionRepo.findNextVersionNumber(entity.getId()));
            v.setName(entity.getName());
            v.setDescription(entity.getDescription());
            v.setPromptText(entity.getPromptText());
            v.setActive(entity.getActive());
            v.setReason(reason);
            v.setCreatedBy(currentUsername());
            versionRepo.save(v);
            log.info("bot_prompt_template_version creada: template_id={} v#{} reason={}",
                    entity.getId(), v.getVersionNumber(), reason);
        } catch (Exception e) {
            // Best-effort: si falla la creación de versión, NO bloqueamos el
            // update — preferimos un update sin histórico que un error visible
            // al usuario. Queda warn en logs (que el monitor captura igual).
            log.warn("[BotPromptTemplate] no se pudo crear versión para template id={}: {}",
                    entity.getId(), e.getMessage());
        }
    }

    /**
     * Username del operador actual desde el SecurityContext. Sincrónico
     * (no @Async) — se llama desde dentro del request thread.
     */
    private String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            Object p = auth.getPrincipal();
            if ("anonymousUser".equals(p)) return null;
            return auth.getName();
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotPromptTemplateDto {
        public Long    id;
        public String  name;
        public String  description;
        public String  promptText;
        public Boolean active;
        public Instant updatedAt;

        /**
         * Campo opcional EN INPUTS — el frontend puede mandar {@code reason}
         * en el PUT para que la versión que se crea quede etiquetada con un
         * label custom (ej: "ai-generate" cuando el generador con IA pisó el
         * prompt). Si no viene, default "edit".
         */
        public String reason;

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

    /**
     * DTO de listado de versiones — SIN el promptText para que el response
     * sea liviano. Para ver el prompt de una versión hay que pedir el detalle.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptVersionListItemDto {
        public Long    id;
        public Long    templateId;
        public Integer versionNumber;
        public String  name;
        public String  description;
        public Boolean active;
        public String  reason;
        public String  createdBy;
        public Instant createdAt;
        /** Caracteres del prompt — para mostrar "12,345 chars" sin traer el texto. */
        public Integer promptLength;

        public static PromptVersionListItemDto fromEntity(BotPromptTemplateVersion v) {
            PromptVersionListItemDto d = new PromptVersionListItemDto();
            d.id            = v.getId();
            d.templateId    = v.getTemplateId();
            d.versionNumber = v.getVersionNumber();
            d.name          = v.getName();
            d.description   = v.getDescription();
            d.active        = v.getActive();
            d.reason        = v.getReason();
            d.createdBy     = v.getCreatedBy();
            d.createdAt     = v.getCreatedAt();
            d.promptLength  = v.getPromptText() != null ? v.getPromptText().length() : 0;
            return d;
        }
    }

    /** DTO de detalle — incluye el promptText completo. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptVersionDetailDto {
        public Long    id;
        public Long    templateId;
        public Integer versionNumber;
        public String  name;
        public String  description;
        public String  promptText;
        public Boolean active;
        public String  reason;
        public String  createdBy;
        public Instant createdAt;

        public static PromptVersionDetailDto fromEntity(BotPromptTemplateVersion v) {
            PromptVersionDetailDto d = new PromptVersionDetailDto();
            d.id            = v.getId();
            d.templateId    = v.getTemplateId();
            d.versionNumber = v.getVersionNumber();
            d.name          = v.getName();
            d.description   = v.getDescription();
            d.promptText    = v.getPromptText();
            d.active        = v.getActive();
            d.reason        = v.getReason();
            d.createdBy     = v.getCreatedBy();
            d.createdAt     = v.getCreatedAt();
            return d;
        }
    }
}
