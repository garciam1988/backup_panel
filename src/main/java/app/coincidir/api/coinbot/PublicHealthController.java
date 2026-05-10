package app.coincidir.api.coinbot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * PublicHealthController — endpoint público súper liviano para que el bot
 * pueda detectar si el backend está vivo antes de mostrarle la UI al usuario.
 *
 *   GET /api/public/health → { "ok": true, "time": "2026-05-09T20:30:00Z" }
 *
 * NO toca BD, NO autentica, NO depende de servicios externos. Solo confirma
 * que el JAR está corriendo y atendiendo HTTP. Si el bot no recibe respuesta
 * (timeout, network error, 5xx), muestra la pantalla de mantenimiento.
 *
 * Si necesitamos health más profundo en el futuro, podemos agregar
 * /api/public/health/db que pingee la BD, pero para "está vivo" alcanza
 * con esto.
 */
@RestController
@RequestMapping("/api/public/health")
public class PublicHealthController {

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("time", Instant.now().toString());
        return out;
    }
}
