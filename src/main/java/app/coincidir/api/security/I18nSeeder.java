package app.coincidir.api.security;

import app.coincidir.api.domain.Language;
import app.coincidir.api.domain.UiText;
import app.coincidir.api.repository.LanguageRepository;
import app.coincidir.api.repository.UiTextRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I18nSeeder — al boot, garantiza:
 *  1) Que existen los 3 idiomas system: es (default), en, pt-BR.
 *  2) Que están sembrados todos los UiText (strings master) que el bot usa.
 *
 * Las strings nuevas que se agregan al código se siembran automáticamente.
 * Si una string ya existe en BD, NO se sobreescribe (preserva ediciones del admin).
 *
 * Para agregar una nueva string al bot:
 *  - Agregala al mapa {@code SEED_TEXTS} acá abajo.
 *  - En el frontend del bot, usá {@code t("la_key")} en lugar del literal.
 *  - Al reiniciar el backend, la string aparece en BD; al recargar el bot, se ve.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class I18nSeeder {

    private final LanguageRepository languageRepo;
    private final UiTextRepository uiTextRepo;

    @PostConstruct
    @Transactional
    public void seed() {
        seedLanguages();
        seedUiTexts();
    }

    // ─── Idiomas por defecto ────────────────────────────────────────────────
    private void seedLanguages() {
        seedLanguage("es",    "Español",              "Español",    "🇦🇷", true,  true,  0);
        seedLanguage("en",    "Inglés",               "English",    "🇺🇸", false, true,  10);
        seedLanguage("pt-BR", "Portugués (Brasil)",   "Português",  "🇧🇷", false, true,  20);
    }

    private void seedLanguage(String code, String name, String nativeName,
                              String flag, boolean isDefault, boolean isSystem, int order) {
        Language existing = languageRepo.findByCode(code).orElse(null);
        if (existing != null) {
            // Re-aseguramos invariantes mínimas (system flag, default si corresponde)
            boolean dirty = false;
            if (isSystem && !Boolean.TRUE.equals(existing.getIsSystem())) {
                existing.setIsSystem(Boolean.TRUE); dirty = true;
            }
            if (dirty) languageRepo.save(existing);
            return;
        }
        Language l = new Language();
        l.setCode(code);
        l.setName(name);
        l.setNativeName(nativeName);
        l.setFlag(flag);
        l.setIsDefault(isDefault);
        l.setIsSystem(isSystem);
        l.setEnabled(true);
        l.setDisplayOrder(order);
        languageRepo.save(l);
        log.info("[I18nSeeder] Idioma sembrado: {}", code);
    }

    // ─── Strings master del bot ─────────────────────────────────────────────
    /**
     * Mapa de todos los textos que aparecen en la UI del bot, en español.
     *
     * Convención de keys:
     *   - prefijo por área (input_, btn_, mode_, menu_, status_, error_, etc.)
     *   - lower_snake_case
     *
     * Si pasás de 1-2 palabras, agregá una description en {@link UiTextSeed}
     * para que la IA traduzca con buen contexto.
     */
    private static final UiTextSeed[] SEED_TEXTS = {
        // ── Chat input ─────────────────────────────────────────────────────
        s("input_placeholder", "Escribí, adjuntá un archivo o grabá un audio...", "input", "Placeholder del campo de texto principal del chat"),
        s("input_placeholder_recording", "🎙️  Hablá ahora, el texto aparece acá...", "input", "Placeholder cuando el cliente está grabando audio"),
        s("input_placeholder_blocked", "Conversación cerrada. Un asesor humano se pondrá en contacto.", "input", "Placeholder cuando la conversación fue bloqueada por fraude"),

        // ── Botones genéricos ──────────────────────────────────────────────
        s("send", "Enviar", "buttons", "Botón principal: enviar mensaje"),
        s("cancel", "Cancelar", "buttons", "Botón cancelar acción"),
        s("retry", "Reintentar", "buttons", "Botón reintentar tras error"),
        s("close", "Cerrar", "buttons", "Botón cerrar modal o popup"),
        s("back", "Volver", "buttons", "Botón volver atrás"),
        s("save", "Guardar", "buttons", "Botón guardar"),
        s("accept", "Aceptar", "buttons", null),
        s("confirm", "Confirmar", "buttons", null),

        // ── Estados ────────────────────────────────────────────────────────
        s("loading", "Cargando…", "status", "Mensaje genérico de carga"),
        s("connecting", "Conectando…", "status", null),
        s("typing", "Escribiendo…", "status", "El bot está generando una respuesta"),
        s("recording_now", "Grabando", "status", "El usuario está grabando audio"),
        s("recording_hint", "Grabando... tocá ■ para detener", "status", "Hint visible mientras se graba"),
        s("processing", "Procesando…", "status", null),
        s("generating_audio", "Generando audio…", "status", "Mientras se genera el TTS de la respuesta"),

        // ── Errores ────────────────────────────────────────────────────────
        s("error_generic", "Ocurrió un error. Reintentá en unos segundos.", "errors", "Mensaje de error genérico para fallos del bot"),
        s("error_network", "No se pudo conectar. Verificá tu conexión.", "errors", null),

        // ── Modal de configuración (rueda) ─────────────────────────────────
        s("settings_title", "Configuración", "settings", "Título del modal de la rueda"),
        s("response_mode", "Modo de respuesta", "settings", "Sección del modal: cómo responde el bot"),
        s("mode_audio_only", "Solo Audio", "settings", "Opción: el bot responde solo por voz"),
        s("mode_audio_desc", "Todo por voz, sin texto", "settings", "Descripción de la opción 'Solo Audio'"),
        s("mode_text_only", "Solo Texto", "settings", "Opción: el bot responde solo por texto"),
        s("mode_text_desc", "Respuesta escrita completa", "settings", "Descripción de la opción 'Solo Texto'"),
        s("language_label", "Idioma", "settings", "Sección del modal: idioma de preferencia"),
        s("language_desc", "Elegí el idioma en el que querés conversar", "settings", null),

        // ── Chips de accesos rápidos (default) ─────────────────────────────
        s("chip_promotions", "Promociones", "chips", "Chip de acceso rápido: ver promociones"),
        s("chip_payment_methods", "Formas de Pago", "chips", "Chip de acceso rápido: ver métodos de pago"),
        s("chip_contact_human", "Hablar con humano", "chips", "Chip de acceso rápido: escalar a humano"),

        // ── Saludo ─────────────────────────────────────────────────────────
        s("greeting", "¡Hola! 👋 Soy {name}. ¿En qué te puedo ayudar?", "welcome", "Saludo inicial del bot. {name} es el nombre del bot."),
        s("greeting_fallback", "¡Hola! ¿En qué te puedo ayudar?", "welcome", "Saludo inicial cuando no hay nombre de bot configurado"),

        // ── Header del chat ────────────────────────────────────────────────
        s("header_title_with_name", "Hola, soy {name}", "header", "Título del chat cuando hay nombre de bot. {name} = nombre"),
        s("header_title_generic", "Asistente virtual", "header", "Título del chat cuando no hay nombre"),
        s("header_subtitle_default", "Estoy acá para ayudarte", "header", "Subtítulo bajo el título del chat"),
        s("header_online", "En línea", "header", "Indicador de estado: el bot está disponible"),

        // ── Menú digital (drawer y preview) ────────────────────────────────
        s("menu_search_placeholder", "🔍 Buscar por nombre, descripción o tag…", "menu", "Buscador del menú digital de productos"),
        s("menu_featured", "⭐ Destacados", "menu", "Sección de ítems destacados del menú digital"),
        s("menu_all", "Todo", "menu", "Filtro: ver todos los ítems del menú"),
        s("menu_add", "Agregar", "menu", "Botón para agregar un ítem del menú al pedido"),
        s("menu_order_line", "Quiero pedir:", "menu", "Frase intro al armar un pedido"),
        s("menu_empty", "Pronto sumamos más opciones. ¡Contactanos!", "menu", "Mensaje cuando el menú está vacío"),
        s("menu_no_results", "No encontramos resultados. Probá otra búsqueda.", "menu", null),
        s("menu_loading_more", "Cargando más…", "menu", null),
        s("menu_end_suffix_singular", "ítem", "menu", "Sufijo singular para conteo: '1 ítem'"),
        s("menu_end_suffix_plural", "ítems", "menu", "Sufijo plural para conteo: '5 ítems'"),
        s("menu_categories_singular", "categoría", "menu", null),
        s("menu_categories_plural", "categorías", "menu", null),
        s("menu_end", "· FIN · {count} {suffix}", "menu", "Línea final del menú digital"),
        s("menu_price_symbol", "$", "menu", "Símbolo de moneda. En portugués brasileño cambia a R$, en otros podría ser USD, etc."),

        // ── Adjuntos / archivos ────────────────────────────────────────────
        s("attachments_clear", "Quitar", "attachments", "Botón para quitar un adjunto pendiente"),
        s("show_video", "Ver video", "buttons", null),
        s("hide", "Ocultar", "buttons", null),

        // ── Avisos / confirmaciones ────────────────────────────────────────
        s("with_this_data", "Con estos datos:", "confirms", "Antes de listar datos en una confirmación"),
        s("contact_human_intro", "Te voy a conectar con un asesor humano.", "fallback", null),
    };

    private void seedUiTexts() {
        for (UiTextSeed s : SEED_TEXTS) {
            if (uiTextRepo.existsByKey(s.key)) continue;
            UiText t = new UiText();
            t.setKey(s.key);
            t.setDefaultText(s.text);
            t.setCategory(s.category);
            t.setDescription(s.description);
            t.setIsSystem(true);
            uiTextRepo.save(t);
        }
        log.info("[I18nSeeder] UiTexts sembrados (total existentes: {})", uiTextRepo.count());
    }

    private static UiTextSeed s(String key, String text, String category, String desc) {
        return new UiTextSeed(key, text, category, desc);
    }

    private record UiTextSeed(String key, String text, String category, String description) {}
}
