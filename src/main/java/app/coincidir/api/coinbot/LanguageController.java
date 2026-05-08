package app.coincidir.api.coinbot;

import app.coincidir.api.domain.Language;
import app.coincidir.api.repository.LanguageRepository;
import app.coincidir.api.repository.UiTranslationRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * LanguageController — CRUD de idiomas. Solo accesible para usuarios con
 * acceso a la sección "languages" del /admin.
 *
 *   GET    /api/admin/languages
 *   POST   /api/admin/languages
 *   PUT    /api/admin/languages/{id}
 *   DELETE /api/admin/languages/{id}
 *   POST   /api/admin/languages/{id}/set-default
 *   POST   /api/admin/languages/{id}/regenerate-translations  body: {overrideManual?: bool}
 */
@RestController
@RequestMapping("/api/admin/languages")
@RequiredArgsConstructor
public class LanguageController {

    private final LanguageRepository repo;
    private final UiTranslationRepository transRepo;
    private final PermissionsService permissionsService;
    private final UiI18nService uiI18nService;

    private static final String SECTION_KEY = "languages";

    @GetMapping
    @Transactional(readOnly = true)
    public List<LanguageDto> list(Authentication auth) {
        requireAccess(auth);
        return repo.findAllByOrderByDisplayOrderAscNameAsc().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public LanguageDto create(@RequestBody SaveRequest body, Authentication auth) {
        requireAccess(auth);
        if (body.code == null || body.code.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código del idioma obligatorio");
        if (body.name == null || body.name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre del idioma obligatorio");

        String code = normalizeCode(body.code);
        if (repo.existsByCodeIgnoreCase(code))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un idioma con ese código");

        Language l = new Language();
        l.setCode(code);
        l.setName(body.name.trim());
        l.setNativeName(emptyToNull(body.nativeName));
        l.setFlag(emptyToNull(body.flag));
        l.setVoiceId(emptyToNull(body.voiceId));
        l.setEnabled(body.enabled == null ? Boolean.TRUE : body.enabled);
        l.setDisplayOrder(body.displayOrder == null ? 100 : body.displayOrder);
        l.setIsDefault(Boolean.FALSE);
        l.setIsSystem(Boolean.FALSE);
        return toDto(repo.save(l));
    }

    @PutMapping("/{id}")
    @Transactional
    public LanguageDto update(@PathVariable Long id, @RequestBody SaveRequest body, Authentication auth) {
        requireAccess(auth);
        Language l = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Idioma no encontrado"));

        // El code de un idioma system no puede cambiarse
        if (Boolean.TRUE.equals(l.getIsSystem())) {
            if (body.code != null && !normalizeCode(body.code).equals(l.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede cambiar el código de un idioma del sistema");
            }
        } else if (body.code != null && !body.code.isBlank()) {
            String newCode = normalizeCode(body.code);
            if (!newCode.equals(l.getCode())) {
                if (repo.existsByCodeIgnoreCase(newCode))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un idioma con ese código");
                // Migramos las traducciones cacheadas con el código viejo al nuevo
                List<app.coincidir.api.domain.UiTranslation> migrate = transRepo.findByLanguageCode(l.getCode());
                for (var t : migrate) t.setLanguageCode(newCode);
                transRepo.saveAll(migrate);
                l.setCode(newCode);
            }
        }

        if (body.name != null && !body.name.isBlank()) l.setName(body.name.trim());
        if (body.nativeName != null) l.setNativeName(emptyToNull(body.nativeName));
        if (body.flag != null) l.setFlag(emptyToNull(body.flag));
        if (body.voiceId != null) l.setVoiceId(emptyToNull(body.voiceId));
        if (body.enabled != null) l.setEnabled(body.enabled);
        if (body.displayOrder != null) l.setDisplayOrder(body.displayOrder);
        return toDto(repo.save(l));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication auth) {
        requireAccess(auth);
        Language l = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Idioma no encontrado"));
        if (Boolean.TRUE.equals(l.getIsSystem()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede borrar un idioma del sistema");
        if (Boolean.TRUE.equals(l.getIsDefault()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede borrar el idioma default");

        // Limpiamos sus traducciones cacheadas
        transRepo.deleteByLanguageCode(l.getCode());
        repo.delete(l);
    }

    @PostMapping("/{id}/set-default")
    @Transactional
    public LanguageDto setDefault(@PathVariable Long id, Authentication auth) {
        requireAccess(auth);
        Language target = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Idioma no encontrado"));
        if (!Boolean.TRUE.equals(target.getEnabled()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El idioma debe estar habilitado para ser default");

        // Quitamos el default actual y ponemos este
        for (Language l : repo.findAll()) {
            if (Boolean.TRUE.equals(l.getIsDefault()) && !l.getId().equals(target.getId())) {
                l.setIsDefault(Boolean.FALSE);
                repo.save(l);
            }
        }
        target.setIsDefault(Boolean.TRUE);
        return toDto(repo.save(target));
    }

    @PostMapping("/{id}/regenerate-translations")
    public RegenResponse regenerate(@PathVariable Long id,
                                    @RequestBody(required = false) RegenRequest body,
                                    Authentication auth) {
        requireAccess(auth);
        Language l = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Idioma no encontrado"));
        boolean override = body != null && Boolean.TRUE.equals(body.overrideManual);
        int saved = uiI18nService.regenerateLanguage(l.getCode(), override);
        RegenResponse r = new RegenResponse();
        r.languageCode = l.getCode();
        r.translatedCount = saved;
        r.overrideManual = override;
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void requireAccess(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        if (!perms.fullAccess() && !perms.hasAdminSection(SECTION_KEY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés acceso a la sección de Lenguajes");
        }
    }

    private static String normalizeCode(String c) {
        return c.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private LanguageDto toDto(Language l) {
        LanguageDto d = new LanguageDto();
        d.id = l.getId();
        d.code = l.getCode();
        d.name = l.getName();
        d.nativeName = l.getNativeName();
        d.flag = l.getFlag();
        d.voiceId = l.getVoiceId();
        d.enabled = l.getEnabled();
        d.isDefault = l.getIsDefault();
        d.isSystem = l.getIsSystem();
        d.displayOrder = l.getDisplayOrder();
        d.createdAt = l.getCreatedAt();
        d.updatedAt = l.getUpdatedAt();
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public String code;
        public String name;
        public String nativeName;
        public String flag;
        public String voiceId;
        public Boolean enabled;
        public Integer displayOrder;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LanguageDto {
        public Long id;
        public String code;
        public String name;
        public String nativeName;
        public String flag;
        public String voiceId;
        public Boolean enabled;
        public Boolean isDefault;
        public Boolean isSystem;
        public Integer displayOrder;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RegenRequest {
        public Boolean overrideManual;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RegenResponse {
        public String languageCode;
        public int translatedCount;
        public Boolean overrideManual;
    }
}
