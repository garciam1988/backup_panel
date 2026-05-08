package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PublicBotConfigController — lectura PÚBLICA de la config del bot.
 *
 * El bot frontend (CoinBot.jsx) lo consumen visitantes anónimos sin token.
 * Antes el frontend se logueaba contra /api/user/auth/login con un usuario
 * técnico ("coinbot") para poder consumir /api/admin/bot-config, pero al
 * endurecer la autenticación del /admin (sólo PanelUser) ese flow se rompió.
 *
 * Solución limpia: exponemos un endpoint público read-only para que el bot
 * lea su propia config sin necesidad de auth. El endpoint admin sigue
 * existiendo para AdminPanel (con auth) y conserva el PUT/POST de reset.
 *
 * IMPORTANTE: este endpoint sólo expone los campos que se renderizan en el
 * frontend del bot (públicos por naturaleza: nombre, colores, header,
 * mensaje de bienvenida, theme, etc.). Si BotConfigDto evoluciona para
 * incluir secretos (API keys, etc.), filtrarlos acá antes de responder.
 *   GET /api/public/bot-config → BotConfigDto (mismo shape que /api/admin/bot-config)
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-config")
@RequiredArgsConstructor
public class PublicBotConfigController {

    private static final Long SINGLETON_ID = 1L;

    private final BotConfigRepository repo;

    @GetMapping
    public BotConfigController.BotConfigDto get() {
        BotConfig entity = repo.findById(SINGLETON_ID).orElseThrow(() -> {
            log.warn("[public/bot-config] No existe la config singleton (id=1).");
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "bot_config no inicializada");
        });
        return BotConfigController.BotConfigDto.fromEntity(entity);
    }
}
