package app.coincidir.api.coinbot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TranslationController — endpoint legacy para traducir batches puntuales.
 *
 * POST /api/admin/translate
 *   body: { "targetLanguage": "en" | "pt-BR" | "es" | "it" | ..., "entries": { "key1": "texto 1", ... } }
 *   resp: { "entries": { "key1": "translation 1", ... } }
 *
 * NOTA: la lógica fue extraída a {@link TranslationService} para que pueda
 * ser usada también por el sistema de i18n del bot (UiI18nService). Este
 * controller mantiene el contrato exacto del endpoint para no romper
 * código del frontend que ya lo consume.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/translate")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationService translationService;

    @PostMapping
    public TranslateResponse translate(@RequestBody TranslateRequest body) {
        if (body == null || body.entries == null || body.entries.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta 'entries'");

        Map<String, String> translated = translationService.translate(
                new LinkedHashMap<>(body.entries),
                body.targetLanguage
        );
        TranslateResponse out = new TranslateResponse();
        out.entries = translated;
        return out;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateRequest {
        public String targetLanguage;
        public Map<String, String> entries;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TranslateResponse {
        public Map<String, String> entries;
    }
}
