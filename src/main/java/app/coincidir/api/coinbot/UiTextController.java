package app.coincidir.api.coinbot;

import app.coincidir.api.domain.UiText;
import app.coincidir.api.domain.UiTranslation;
import app.coincidir.api.repository.UiTextRepository;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * UiTextController — admin de las strings master del bot y sus traducciones.
 *
 *   GET    /api/admin/ui-texts                     — listar todas las strings master con sus traducciones
 *   POST   /api/admin/ui-texts                     — crear nueva string custom
 *   PUT    /api/admin/ui-texts/{id}                — editar (texto en es, descripción)
 *   DELETE /api/admin/ui-texts/{id}                — borrar (sólo no-system)
 *   PUT    /api/admin/ui-texts/{id}/translation    — editar manualmente la traducción a un idioma
 *
 * Acceso gobernado por la sección "translations" del RBAC.
 */
@RestController
@RequestMapping("/api/admin/ui-texts")
@RequiredArgsConstructor
public class UiTextController {

    private final UiTextRepository textRepo;
    private final UiTranslationRepository transRepo;
    private final PermissionsService permissionsService;
    private final UiI18nService uiI18nService;

    private static final String SECTION_KEY = "translations";

    @GetMapping
    @Transactional(readOnly = true)
    public List<UiTextDto> list(Authentication auth) {
        requireAccess(auth);

        List<UiText> texts = textRepo.findAllByOrderByCategoryAscKeyAsc();
        // Cargamos todas las traducciones de una para no hacer N queries
        List<UiTranslation> all = transRepo.findAll();
        Map<Long, List<UiTranslation>> byTextId = all.stream()
                .collect(Collectors.groupingBy(UiTranslation::getUiTextId));

        return texts.stream().map(t -> {
            UiTextDto d = toDto(t);
            List<UiTranslation> trs = byTextId.getOrDefault(t.getId(), Collections.emptyList());
            d.translations = new HashMap<>();
            for (UiTranslation tr : trs) {
                TranslationDto tdto = new TranslationDto();
                tdto.text = tr.getTranslatedText();
                tdto.source = tr.getSource() == null ? "AI" : tr.getSource().name();
                tdto.updatedAt = tr.getUpdatedAt();
                d.translations.put(tr.getLanguageCode(), tdto);
            }
            return d;
        }).toList();
    }

    @PostMapping
    @Transactional
    public UiTextDto create(@RequestBody SaveRequest body, Authentication auth) {
        requireAccess(auth);
        if (body.key == null || body.key.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Key obligatorio");
        if (body.defaultText == null || body.defaultText.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Texto en español obligatorio");

        String key = body.key.trim();
        if (textRepo.existsByKey(key))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una string con ese key");

        UiText t = new UiText();
        t.setKey(key);
        t.setDefaultText(body.defaultText);
        t.setCategory(emptyToNull(body.category));
        t.setDescription(emptyToNull(body.description));
        t.setIsSystem(false);
        return toDto(textRepo.save(t));
    }

    @PutMapping("/{id}")
    @Transactional
    public UiTextDto update(@PathVariable Long id, @RequestBody SaveRequest body, Authentication auth) {
        requireAccess(auth);
        UiText t = textRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Sólo se puede cambiar el key si no es system
        if (Boolean.TRUE.equals(t.getIsSystem())) {
            if (body.key != null && !body.key.trim().equals(t.getKey())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede cambiar el key de una string del sistema");
            }
        } else if (body.key != null && !body.key.isBlank()) {
            String newKey = body.key.trim();
            if (!newKey.equals(t.getKey())) {
                if (textRepo.existsByKey(newKey))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una string con ese key");
                t.setKey(newKey);
            }
        }

        boolean textChanged = false;
        if (body.defaultText != null && !body.defaultText.equals(t.getDefaultText())) {
            t.setDefaultText(body.defaultText);
            textChanged = true;
        }
        if (body.category != null) t.setCategory(emptyToNull(body.category));
        if (body.description != null) t.setDescription(emptyToNull(body.description));

        UiText saved = textRepo.save(t);

        // Si cambió el texto en español, las traducciones cacheadas quedan
        // desactualizadas. Las invalidamos (excepto las MANUAL, que el admin
        // ya curó él mismo y probablemente no quiera perder).
        if (textChanged) {
            uiI18nService.invalidateText(saved.getId(), true);
        }
        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication auth) {
        requireAccess(auth);
        UiText t = textRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Boolean.TRUE.equals(t.getIsSystem()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede borrar una string del sistema (es referenciada por el código del bot)");
        transRepo.deleteByUiTextId(t.getId());
        textRepo.delete(t);
    }

    /** Editar manualmente una traducción a un idioma específico. */
    @PutMapping("/{id}/translation")
    @Transactional
    public TranslationDto setTranslation(@PathVariable Long id,
                                         @RequestBody SetTranslationRequest body,
                                         Authentication auth) {
        requireAccess(auth);
        if (body == null || body.languageCode == null || body.languageCode.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "languageCode obligatorio");

        UiText t = textRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        UiTranslation tr = transRepo.findByUiTextIdAndLanguageCode(t.getId(), body.languageCode)
                .orElseGet(() -> {
                    UiTranslation nt = new UiTranslation();
                    nt.setUiTextId(t.getId());
                    nt.setLanguageCode(body.languageCode);
                    return nt;
                });
        tr.setTranslatedText(body.text == null ? "" : body.text);
        tr.setSource(UiTranslation.Source.MANUAL);
        UiTranslation saved = transRepo.save(tr);

        TranslationDto d = new TranslationDto();
        d.text = saved.getTranslatedText();
        d.source = saved.getSource().name();
        d.updatedAt = saved.getUpdatedAt();
        return d;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void requireAccess(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        if (!perms.fullAccess() && !perms.hasAdminSection(SECTION_KEY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tenés acceso a la sección de Textos del frontend");
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private UiTextDto toDto(UiText t) {
        UiTextDto d = new UiTextDto();
        d.id = t.getId();
        d.key = t.getKey();
        d.defaultText = t.getDefaultText();
        d.category = t.getCategory();
        d.description = t.getDescription();
        d.isSystem = t.getIsSystem();
        d.createdAt = t.getCreatedAt();
        d.updatedAt = t.getUpdatedAt();
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SaveRequest {
        public String key;
        public String defaultText;
        public String category;
        public String description;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SetTranslationRequest {
        public String languageCode;
        public String text;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UiTextDto {
        public Long id;
        public String key;
        public String defaultText;
        public String category;
        public String description;
        public Boolean isSystem;
        public Instant createdAt;
        public Instant updatedAt;
        public Map<String, TranslationDto> translations;  // langCode → traducción
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslationDto {
        public String text;
        public String source;        // AI | MANUAL
        public Instant updatedAt;
    }
}
