package app.coincidir.api.coinbot;

import app.coincidir.api.domain.Language;
import app.coincidir.api.repository.LanguageRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PublicI18nController — endpoints PÚBLICOS (sin autenticación) consumidos
 * por el frontend del bot que ven los visitantes/clientes.
 *
 *   GET  /api/bot/languages         → lista de idiomas habilitados (para el selector)
 *   GET  /api/bot/i18n?lang=xx      → bundle key→texto para ese idioma (con lazy translation)
 *   POST /api/bot/translate-custom  → traduce textos custom del bot (welcomeMessage, etc.)
 *                                     al idioma del cliente. Sin persistir en BD.
 *
 * Asegurate de que estos paths estén en el SecurityConfig como permitAll.
 */
@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
public class PublicI18nController {

    private final LanguageRepository langRepo;
    private final UiI18nService uiI18nService;
    private final TranslationService translationService;

    @GetMapping("/languages")
    public List<PublicLangDto> listLanguages() {
        return langRepo.findAllByEnabledTrueOrderByDisplayOrderAscNameAsc()
                .stream().map(this::toPublic).toList();
    }

    @GetMapping("/i18n")
    public BundleResponse bundle(@RequestParam(value = "lang", required = false) String lang) {
        Map<String, String> entries = uiI18nService.getBundle(lang);
        BundleResponse r = new BundleResponse();
        r.lang = lang;
        r.entries = entries;

        // Atajamos el voiceId también para que el frontend pueda configurar TTS
        // sin hacer una segunda llamada
        Language l = langRepo.findByCodeIgnoreCase(lang == null ? "" : lang).orElse(null);
        if (l != null) {
            r.lang = l.getCode();
            r.voiceId = l.getVoiceId();
            r.flag = l.getFlag();
            r.name = l.getName();
            r.nativeName = l.getNativeName();
        }
        return r;
    }

    /**
     * Traduce un mapa de textos custom del bot (welcomeMessage, headerTitle,
     * chips de quickAccess, etc.) al idioma destino. NO persiste el resultado
     * en BD — el frontend cachea en localStorage por hash de los textos source.
     *
     * Usa el mismo TranslationService que las strings de UI.
     * Política fail-soft: si la API falla, devuelve los originales.
     */
    @PostMapping("/translate-custom")
    public TranslateCustomResponse translateCustom(@RequestBody TranslateCustomRequest body) {
        if (body == null || body.entries == null || body.entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta 'entries'");
        }
        String target = body.targetLanguage == null ? "" : body.targetLanguage.trim();

        // Si target es vacío, español o no existe en BD, devolvemos los originales
        if (target.isEmpty() || "es".equalsIgnoreCase(target)) {
            TranslateCustomResponse r = new TranslateCustomResponse();
            r.entries = new LinkedHashMap<>(body.entries);
            return r;
        }

        // Buscamos el idioma para tomar su nativeName/name como label de prompt
        Language l = langRepo.findByCodeIgnoreCase(target).orElse(null);
        String label = l == null ? null : (l.getNativeName() != null ? l.getNativeName() : l.getName());

        Map<String, String> translated = translationService.translate(
                new LinkedHashMap<>(body.entries), target, label
        );
        TranslateCustomResponse r = new TranslateCustomResponse();
        r.entries = translated;
        return r;
    }

    private PublicLangDto toPublic(Language l) {
        PublicLangDto d = new PublicLangDto();
        d.code = l.getCode();
        d.name = l.getName();
        d.nativeName = l.getNativeName();
        d.flag = l.getFlag();
        d.isDefault = Boolean.TRUE.equals(l.getIsDefault());
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicLangDto {
        public String code;
        public String name;
        public String nativeName;
        public String flag;
        public Boolean isDefault;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BundleResponse {
        public String lang;
        public String voiceId;
        public String flag;
        public String name;
        public String nativeName;
        public Map<String, String> entries = new HashMap<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateCustomRequest {
        public String targetLanguage;
        public Map<String, String> entries;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateCustomResponse {
        public Map<String, String> entries;
    }
}
