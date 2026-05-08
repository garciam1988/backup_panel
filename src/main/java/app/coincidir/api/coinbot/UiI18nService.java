package app.coincidir.api.coinbot;

import app.coincidir.api.domain.Language;
import app.coincidir.api.domain.UiText;
import app.coincidir.api.domain.UiTranslation;
import app.coincidir.api.repository.LanguageRepository;
import app.coincidir.api.repository.UiTextRepository;
import app.coincidir.api.repository.UiTranslationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * UiI18nService — gestiona las traducciones de los textos de UI del bot.
 *
 * Flujo principal (lazy):
 *  1. El frontend del bot pide los textos para un idioma: {@link #getBundle(String)}.
 *  2. El servicio mira las traducciones cacheadas en BD para ese idioma.
 *  3. Si faltan algunas, llama a {@link TranslationService} con las strings
 *     pendientes y guarda los resultados en BD.
 *  4. Devuelve un mapa key → texto traducido.
 *
 * Para el idioma {@code es} (idioma fuente) directamente devuelve los
 * {@code defaultText} de cada UiText.
 *
 * Las traducciones marcadas como {@code MANUAL} (editadas a mano por el admin)
 * se respetan: no se sobrescriben en regeneraciones automáticas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UiI18nService {

    private final UiTextRepository uiTextRepo;
    private final UiTranslationRepository uiTransRepo;
    private final LanguageRepository languageRepo;
    private final TranslationService translationService;

    /**
     * Devuelve el bundle completo de strings para un idioma dado.
     * Mapea key → texto en el idioma destino.
     *
     * @param langCode idioma destino. Si es null/blank o no existe, se usa
     *                 el idioma default (típicamente "es").
     */
    @Transactional
    public Map<String, String> getBundle(String langCode) {
        // Resolver idioma efectivo
        Language lang = resolveLanguage(langCode);
        String effective = lang == null ? "es" : lang.getCode();

        List<UiText> allTexts = uiTextRepo.findAll();
        Map<String, String> out = new LinkedHashMap<>();

        // Si es el idioma fuente, devolvemos los defaultText directamente
        if ("es".equalsIgnoreCase(effective)) {
            for (UiText t : allTexts) out.put(t.getKey(), t.getDefaultText());
            return out;
        }

        // Cargamos traducciones existentes para ese idioma
        List<UiTranslation> existing = uiTransRepo.findByLanguageCode(effective);
        Map<Long, UiTranslation> byTextId = new HashMap<>(existing.size() * 2);
        for (UiTranslation tr : existing) byTextId.put(tr.getUiTextId(), tr);

        // Identificamos las que faltan (no están en BD)
        Map<String, String> pending = new LinkedHashMap<>();
        Map<String, Long> pendingKeyToId = new HashMap<>();
        for (UiText t : allTexts) {
            UiTranslation tr = byTextId.get(t.getId());
            if (tr != null) {
                out.put(t.getKey(), tr.getTranslatedText());
            } else {
                pending.put(t.getKey(), t.getDefaultText());
                pendingKeyToId.put(t.getKey(), t.getId());
            }
        }

        // Traducción lazy: si hay pendientes, las pedimos a Claude y cacheamos
        if (!pending.isEmpty()) {
            log.info("[UiI18nService] Traduciendo {} strings nuevas a {}", pending.size(), effective);
            String label = lang == null ? null : (lang.getNativeName() != null ? lang.getNativeName() : lang.getName());
            Map<String, String> translated = translationService.translate(pending, effective, label);

            for (Map.Entry<String, String> e : translated.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                Long uiTextId = pendingKeyToId.get(key);
                if (uiTextId == null || value == null || value.isBlank()) continue;

                // Guardamos en BD (cache) — incluso si la traducción es igual al original
                // (ej: brand name "YES Travel"). Si fue fail-soft, también la guardamos
                // para no estar reintentando todo el rato.
                UiTranslation tr = new UiTranslation();
                tr.setUiTextId(uiTextId);
                tr.setLanguageCode(effective);
                tr.setTranslatedText(value);
                tr.setSource(UiTranslation.Source.AI);
                try {
                    uiTransRepo.save(tr);
                } catch (Exception ex) {
                    // Posible race condition con otra request: ignoramos (la otra ya guardó)
                    log.debug("[UiI18nService] save race ignored: {}", ex.getMessage());
                }
                out.put(key, value);
            }

            // Para las que NO se tradujeron (fail-soft devolvió igual), igual las
            // ponemos como están en español para que la UI tenga algo que mostrar.
            for (Map.Entry<String, String> e : pending.entrySet()) {
                if (!out.containsKey(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /**
     * Regenera TODAS las traducciones de un idioma con IA. Útil cuando el admin
     * editó muchas strings master o quiere refrescar.
     *
     * @param langCode idioma a regenerar (no puede ser "es")
     * @param overrideManual si true, también sobreescribe las traducciones que
     *                       el admin había editado a mano. Si false, solo las AI.
     */
    @Transactional
    public int regenerateLanguage(String langCode, boolean overrideManual) {
        Language lang = resolveLanguage(langCode);
        if (lang == null) return 0;
        String code = lang.getCode();
        if ("es".equalsIgnoreCase(code)) return 0;

        List<UiText> allTexts = uiTextRepo.findAll();
        List<UiTranslation> existing = uiTransRepo.findByLanguageCode(code);
        Map<Long, UiTranslation> byTextId = new HashMap<>();
        for (UiTranslation tr : existing) byTextId.put(tr.getUiTextId(), tr);

        // Construimos el batch a traducir, omitiendo MANUAL si así se pidió
        Map<String, String> toTranslate = new LinkedHashMap<>();
        Map<String, Long> keyToId = new HashMap<>();
        for (UiText t : allTexts) {
            UiTranslation tr = byTextId.get(t.getId());
            if (tr != null && !overrideManual && tr.getSource() == UiTranslation.Source.MANUAL) {
                continue;
            }
            toTranslate.put(t.getKey(), t.getDefaultText());
            keyToId.put(t.getKey(), t.getId());
        }
        if (toTranslate.isEmpty()) return 0;

        String label = lang.getNativeName() != null ? lang.getNativeName() : lang.getName();
        Map<String, String> translated = translationService.translate(toTranslate, code, label);

        int saved = 0;
        for (Map.Entry<String, String> e : translated.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            Long uiTextId = keyToId.get(key);
            if (uiTextId == null || value == null || value.isBlank()) continue;

            UiTranslation tr = byTextId.get(uiTextId);
            if (tr == null) {
                tr = new UiTranslation();
                tr.setUiTextId(uiTextId);
                tr.setLanguageCode(code);
            }
            tr.setTranslatedText(value);
            tr.setSource(UiTranslation.Source.AI);
            uiTransRepo.save(tr);
            saved++;
        }
        return saved;
    }

    /**
     * Cuando el admin edita el {@code defaultText} de un UiText, las traducciones
     * cacheadas quedan desactualizadas. Las eliminamos para que se regeneren
     * lazy en la próxima llamada (excepto las MANUAL).
     */
    @Transactional
    public void invalidateText(Long uiTextId, boolean keepManual) {
        List<UiTranslation> existing = uiTransRepo.findByUiTextId(uiTextId);
        for (UiTranslation tr : existing) {
            if (keepManual && tr.getSource() == UiTranslation.Source.MANUAL) continue;
            uiTransRepo.delete(tr);
        }
    }

    private Language resolveLanguage(String code) {
        if (code != null && !code.isBlank()) {
            Optional<Language> opt = languageRepo.findByCodeIgnoreCase(code.trim());
            if (opt.isPresent()) return opt.get();
        }
        return languageRepo.findFirstByIsDefaultTrue().orElse(null);
    }
}
