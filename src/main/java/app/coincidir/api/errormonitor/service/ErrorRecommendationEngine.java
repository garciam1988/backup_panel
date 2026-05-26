package app.coincidir.api.errormonitor.service;

import app.coincidir.api.errormonitor.service.ErrorClassifier.Types;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * ErrorRecommendationEngine — produce un texto de recomendación (1-3 frases)
 * para mostrarle al operador en el modal de detalle del error. La idea es
 * que en vez de tener solo un stack trace, el operador vea "esto es un X y
 * lo más probable es que sea Y, probá Z" sin tener que buscar en Google.
 *
 * Es deliberadamente simple: una lista de reglas en cascada. NO usa LLM ni
 * nada externo — queremos respuestas instantáneas y sin costo. Si en el
 * futuro queremos enriquecer con IA, podríamos:
 *   1. Mostrar primero la recomendación rule-based (acá).
 *   2. Botón "Pedir análisis a Claude" que llama a /api/admin/error-monitor/{id}/analyze
 *      y guarda el resultado en data_json.
 *
 * Las recomendaciones son específicas del stack de Matías (Spring Boot +
 * MySQL en Railway, Next.js/Vite, NSSM en Windows Server, etc.) — están en
 * castellano informal porque es la lengua del operador.
 */
@Component
public class ErrorRecommendationEngine {

    /**
     * Devuelve la recomendación para el error. Nunca null — si no hay regla
     * matched devuelve un fallback genérico que igual es útil.
     */
    public String recommend(String errorType, String message, String stack, Integer httpStatus) {
        String hay = combineLower(message, stack);

        // ── DATABASE ──────────────────────────────────────────────────────
        if (Types.DATABASE.equals(errorType)) {
            if (hay.contains("communications link failure")) {
                return "MySQL perdió la conexión con el pool. " +
                       "Causas típicas: (1) Railway reinició el servicio de DB " +
                       "— verificá en su panel; (2) la red del Windows Server se cortó " +
                       "— revisá `nssm status apibot` y los logs de NSSM en " +
                       "C:\\ProgramData\\nssm\\logs; (3) el pool agotó conexiones inactivas " +
                       "(wait_timeout de MySQL > maxLifetime de Hikari). " +
                       "Confirmá `spring.datasource.hikari.max-lifetime` < 28800000 (8h).";
            }
            if (hay.contains("could not get jdbc connection")) {
                return "El pool de Hikari no pudo entregar conexión en el timeout. " +
                       "Subí `spring.datasource.hikari.maximum-pool-size` o revisá si " +
                       "hay queries colgadas (verificá `SHOW PROCESSLIST` en MySQL). " +
                       "También puede ser que MySQL esté caído — chequeá Railway.";
            }
            if (hay.contains("duplicate entry")) {
                return "Una INSERT/UPDATE violó un UNIQUE constraint. " +
                       "Revisá el detalle para ver qué columna y qué valor. " +
                       "Posiblemente el frontend está reintentando con un valor existente " +
                       "(falta deshabilitar el botón Guardar mientras se procesa).";
            }
            if (hay.contains("deadlock found")) {
                return "MySQL detectó un deadlock entre transacciones concurrentes. " +
                       "Es esperable bajo carga — el caller debería reintentar la operación. " +
                       "Si pasa seguido, revisá el orden de UPDATEs en las transacciones " +
                       "(siempre tomar locks en el mismo orden).";
            }
            if (hay.contains("lock wait timeout")) {
                return "Una transacción esperó >50s un lock que no se liberó. " +
                       "Típicamente otra transacción larga olvidó hacer commit/rollback. " +
                       "Buscá en logs queries lentas o conexiones que quedaron abiertas. " +
                       "`SHOW ENGINE INNODB STATUS\\G` muestra el detalle.";
            }
            if (hay.contains("data integrity") || hay.contains("constraint")) {
                return "Violación de FK o NOT NULL. Revisá si el caller mandó " +
                       "un ID inexistente o un campo obligatorio vacío. Si el error " +
                       "aparece después de un cambio de schema, revisá la migración " +
                       "Flyway más reciente.";
            }
            return "Error de MySQL. Revisá el detalle para identificar la causa. " +
                   "Si Railway está OK, probá reiniciar el backend con `nssm restart apibot`.";
        }

        // ── ANTHROPIC ─────────────────────────────────────────────────────
        if (Types.ANTHROPIC_API.equals(errorType)) {
            if (hay.contains("overloaded_error") || (httpStatus != null && httpStatus == 529)) {
                return "Anthropic está sobrecargado (529). Esperá 30-60 segundos y " +
                       "reintentá. Si pasa seguido en horario pico, configurá el routing " +
                       "(/admin → LLM) para que use Haiku como fallback automático cuando " +
                       "Sonnet falla. También considerá habilitar prompt caching para " +
                       "bajar la presión sobre la API.";
            }
            if (hay.contains("rate_limit") || (httpStatus != null && httpStatus == 429)) {
                return "Excediste los límites por minuto de Anthropic (rate limit). " +
                       "Bajá la concurrencia o pediles a Anthropic un aumento de cuota. " +
                       "Para uso de Claude API en producción se puede pedir Tier 2/3 " +
                       "(más rpm y más tokens/min).";
                       
            }
            if (hay.contains("authentication") || (httpStatus != null && httpStatus == 401)) {
                return "API key de Anthropic inválida o revocada. " +
                       "Revisá `coincidir.anthropic.api-key` en application.properties " +
                       "o la variable de entorno ANTHROPIC_API_KEY en Railway. " +
                       "Si rotaste la key recientemente, asegurate de reiniciar el backend.";
            }
            if (hay.contains("invalid_request") || (httpStatus != null && httpStatus == 400)) {
                return "Anthropic rechazó el payload. Casos comunes: tool_use sin " +
                       "tool_result correspondiente, mensajes vacíos, max_tokens > permitido " +
                       "para el modelo, o model string mal escrito. Revisá el detail.";
                       
            }
            return "Error en API de Anthropic. Verificá en https://status.anthropic.com " +
                   "si hay incidente reportado. Sino, revisá la API key y el modelo configurado.";
        }

        if (Types.OPENAI_API.equals(errorType)) {
            return "Error en OpenAI. Verificá la API key y el balance de la cuenta. " +
                   "Si es 429, esperá; si es 401, rotá la key.";
        }

        if (Types.ELEVENLABS_API.equals(errorType)) {
            return "Error en ElevenLabs (TTS). Verificá créditos disponibles en la " +
                   "cuenta y que la voice_id usada exista. Si es timeout, ElevenLabs " +
                   "está lento — fallback a la voz de OpenAI desde /admin → Audio.";
        }

        // ── EMAIL / TWILIO ────────────────────────────────────────────────
        if (Types.EMAIL_SMTP.equals(errorType)) {
            if (hay.contains("authentication"))
                return "Credenciales SMTP inválidas. Revisá SMTP_USER y SMTP_PASS en " +
                       "application.properties. Si usás Wiroos (wo51), confirmá que la " +
                       "cuenta no esté suspendida por uso excesivo.";
            return "No se pudo enviar email. Verificá conectividad SMTP " +
                   "(SMTP_HOST/SMTP_PORT) y que la cuenta esté activa.";
        }
        if (Types.TWILIO.equals(errorType)) {
            return "Error en Twilio. Causas típicas: número WhatsApp no aprobado para " +
                   "el template, saldo insuficiente, sandbox vencido. Verificá en " +
                   "console.twilio.com.";
        }

        // ── AUTH ──────────────────────────────────────────────────────────
        if (Types.AUTH.equals(errorType)) {
            if (hay.contains("jwt expired") || hay.contains("expiredjwt")) {
                return "Token JWT expirado. El frontend debería detectarlo (401) y " +
                       "redirigir al login automáticamente. Si pasa seguido para el mismo " +
                       "usuario, revisá la duración del JWT en la configuración.";
            }
            if (hay.contains("badcredentials"))
                return "Login fallido: usuario o contraseña incorrectos. Si es un " +
                       "operador legítimo, reseteá la password desde /admin → Usuarios.";
            return "Error de autenticación. Si es un endpoint que requiere DIOS y " +
                   "el usuario es operador, eso es esperable — revisá los permisos en " +
                   "/admin → Usuarios y roles.";
        }

        // ── VALIDATION / NOT_FOUND / CONFLICT ─────────────────────────────
        if (Types.VALIDATION.equals(errorType)) {
            return "El payload no pasó las validaciones del backend. Revisá el detail " +
                   "para ver qué campo falló. Si el frontend ya validó antes de mandar, " +
                   "puede haber un desync entre la versión publicada del front y el back " +
                   "(usuario con caché viejo).";
        }
        if (Types.NOT_FOUND.equals(errorType)) {
            return "El recurso pedido no existe. Si era un ID que el usuario tenía " +
                   "abierto, puede haber sido borrado por otro operador. Si era un " +
                   "endpoint, revisá si el path está bien escrito en el frontend.";
        }
        if (Types.CONFLICT.equals(errorType)) {
            return "Conflicto al actualizar — alguien más modificó el registro " +
                   "(optimistic locking). El usuario debería refrescar y reintentar.";
        }

        // ── FLYWAY ────────────────────────────────────────────────────────
        if (Types.FLYWAY.equals(errorType)) {
            return "La migración Flyway falló al arrancar. NO REINICIES LA APP HASTA " +
                   "RESOLVERLO — un baseline mal hecho puede dejar la DB en estado " +
                   "inconsistente. Revisá el archivo problemático en db/migration/ y " +
                   "ejecutá `flyway repair` o resolvé manualmente la entrada en " +
                   "`flyway_schema_history`.";
        }

        // ── FILE / NETWORK / TIMEOUT ──────────────────────────────────────
        if (Types.FILE_IO.equals(errorType)) {
            if (hay.contains("permission denied"))
                return "Permisos insuficientes para acceder al archivo. En Windows " +
                       "Server confirmá que el usuario que corre NSSM tenga acceso a " +
                       "la ruta. En Linux, revisá los chmod.";
            return "Error leyendo o escribiendo un archivo. Verificá que la ruta " +
                   "exista, que haya espacio en disco y que el proceso tenga permisos.";
        }
        if (Types.NETWORK.equals(errorType)) {
            return "Problema de red. Si es `Connection refused` apuntando a " +
                   "localhost, el servicio destino está caído. Si es `UnknownHost`, " +
                   "el DNS no resolvió — probá ping desde el server.";
        }
        if (Types.TIMEOUT.equals(errorType)) {
            return "Timeout en una operación externa. Si es a la DB, mirá `SHOW " +
                   "PROCESSLIST`. Si es a Anthropic/OpenAI, probá aumentar el timeout " +
                   "del cliente HTTP. Si pasa esporádicamente, puede ser red de Railway.";
        }

        // ── FRONTEND ──────────────────────────────────────────────────────
        if (Types.FRONTEND_FETCH.equals(errorType)) {
            return "Un fetch del frontend falló. Si es 401, el JWT venció " +
                   "(esperable, el front redirige al login). Si es 5xx, hay un error " +
                   "del backend correlacionado — buscalo por requestId. Si es " +
                   "`Failed to fetch` sin status, probable CORS o red intermitente.";
        }
        if (Types.FRONTEND_RENDER.equals(errorType)) {
            return "Crash de render en React. Revisá el componente que aparece en el " +
                   "stack — típicamente es un acceso a `obj.algo` cuando `obj` es " +
                   "undefined (data del backend con shape distinto al esperado). " +
                   "Agregá un optional chaining o un loading state para evitarlo.";
        }
        if (Types.FRONTEND_UNHANDLED.equals(errorType)) {
            return "Una Promise rechazada sin .catch(). Revisá el detail para ver " +
                   "qué async function falló. Buena práctica: envolver todos los " +
                   "fetches en try/catch o usar un wrapper como panelFetch que ya " +
                   "maneja errores comunes.";
        }

        // ── Bot — respuestas vacías ──────────────────────────────────────
        if (Types.BOT_EMPTY_RESPONSE.equals(errorType)) {
            if (hay.contains("\"stop_reason\":\"error\"")) {
                return "Anthropic devolvió 200 OK pero con stop_reason=\"error\". " +
                       "Causa típica: el bot intentó hacer un tool_use cuyo input no " +
                       "pasó la validación interna de Anthropic, o se cortó la " +
                       "generación por un filtro de safety. " +
                       "Revisá los últimos mensajes del usuario por si hay contenido " +
                       "problemático, y el schema de tools que estás mandando. " +
                       "Si pasa seguido con un mismo usuario, mirá el chat completo " +
                       "en /admin → Conversaciones.";
            }
            if (hay.contains("\"content\":[]") || hay.contains("\"content\": []")) {
                return "Anthropic devolvió 200 OK con content vacío. " +
                       "Causa: el modelo decidió no responder (puede pasar con prompts " +
                       "ambiguos o con stop_sequences que matchean al toque). " +
                       "Revisá si el prompt del bot tiene stop_sequences que pueden " +
                       "estar disparándose temprano. Si es esporádico, no es un bug — " +
                       "manejá el caso vacío en el frontend del bot mostrando " +
                       "\"perdón, no te entendí, ¿podés repetir?\".";
            }
            return "El bot devolvió una respuesta sin contenido visible para el usuario. " +
                   "Revisá el detalle (body completo de Anthropic) para entender el motivo, " +
                   "y considerá agregar un fallback en el frontend del bot para no dejar " +
                   "al usuario sin respuesta.";
        }

        // ── Bot — tool failures ─────────────────────────────────────────
        if (Types.BOT_TOOL_FAILURE.equals(errorType)) {
            // Sub-clasificación por contenido del error
            if (hay.contains("duplicate entry") || hay.contains("unique constraint")) {
                return "La tool del bot intentó insertar un registro duplicado. " +
                       "El bot debería detectar la situación antes (buscar primero por " +
                       "el campo único). Revisá el system prompt — quizás hay que " +
                       "agregar instrucción tipo \"siempre consultá antes de crear\".";
            }
            if (hay.contains("data integrity") || hay.contains("foreign key") ||
                hay.contains("constraint")) {
                return "La tool violó una FK o constraint de la base. Probable " +
                       "que el bot pasó un ID que no existe (sucursal, cliente, etc.). " +
                       "Revisá los parámetros que pasó la tool en el detail y, si es " +
                       "recurrente, agregá validación en el SQL de la tool desde " +
                       "/admin → Tools del bot.";
            }
            if (hay.contains("required") || hay.contains("validación") ||
                hay.contains("parametros faltantes") || hay.contains("missing")) {
                return "Faltaron parámetros obligatorios cuando el bot invocó la tool. " +
                       "Causas comunes: (1) el modelo no extrajo todos los datos de " +
                       "la conversación; (2) el schema de la tool tiene required que el " +
                       "prompt no enfatiza. Revisá el schema en /admin → Tools del bot " +
                       "y reforzá las instrucciones del prompt para pedir esos datos " +
                       "explícitamente al usuario.";
            }
            if (hay.contains("syntax error") || hay.contains("sql syntax")) {
                return "La query SQL de la tool tiene un error de sintaxis. Es un " +
                       "bug de configuración — revisá la tool en /admin → Tools del bot " +
                       "y probala manualmente con el botón \"Test\".";
            }
            return "Una tool del bot falló al ejecutarse. " +
                   "Revisá el detail para ver qué tool y con qué parámetros. " +
                   "Si es recurrente con un mismo patrón, modificá la tool desde " +
                   "/admin → Tools del bot o reforzá el system prompt para que el bot " +
                   "no la invoque mal.";
        }

        // ── Fallback genérico ─────────────────────────────────────────────
        return "Error sin patrón conocido. Revisá el stack completo en el detalle. " +
               "Si el error se repite, considerá agregar una regla específica al " +
               "ErrorRecommendationEngine para que el panel pueda sugerir una solución " +
               "en el futuro.";
    }

    private String combineLower(String message, String stack) {
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message);
        if (stack != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(stack, 0, Math.min(stack.length(), 4096));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
