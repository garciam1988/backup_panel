package app.coincidir.api.errormonitor.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * ErrorClassifier — convierte un mensaje de error + stack en un errorType
 * estable que sirve para agrupar, filtrar y mostrar en el panel de errores.
 *
 * Diseño:
 *  - El método principal {@link #classify(String, String, String)} recibe
 *    el mensaje, el stack (puede ser null) y un hint opcional del caller
 *    (ej: el frontend manda "fetch" cuando capturó un fetch fallido).
 *  - Recorre patrones en orden de especificidad — primero los más concretos
 *    (Anthropic, Hikari) y al final el catch-all ("INTERNAL").
 *  - No usa regex compilada porque la mayoría son substring checks: más
 *    rápido y más legible. Si en el futuro necesitamos patterns complejos,
 *    convertir a Pattern.compile() una vez por clase.
 *
 * Por qué no usar @Component con configurables: los patrones son una
 * lista cerrada que cambia solo con un deploy. Si hubieran reglas dinámicas
 * editables desde /admin, sí valdría la pena moverlas a una tabla.
 */
@Component
public class ErrorClassifier {

    /**
     * Tipos canónicos. El frontend tiene los labels (en español); acá
     * usamos los codes como contrato estable.
     */
    public static final class Types {
        public static final String DATABASE        = "DATABASE";
        public static final String ANTHROPIC_API   = "ANTHROPIC_API";
        public static final String OPENAI_API      = "OPENAI_API";
        public static final String ELEVENLABS_API  = "ELEVENLABS_API";
        public static final String EMAIL_SMTP      = "EMAIL_SMTP";
        public static final String TWILIO          = "TWILIO";
        public static final String AUTH            = "AUTH";
        public static final String VALIDATION      = "VALIDATION";
        public static final String NOT_FOUND       = "NOT_FOUND";
        public static final String CONFLICT        = "CONFLICT";
        public static final String FILE_IO         = "FILE_IO";
        public static final String FLYWAY          = "FLYWAY";
        public static final String FRONTEND_FETCH  = "FRONTEND_FETCH";
        public static final String FRONTEND_RENDER = "FRONTEND_RENDER";
        public static final String FRONTEND_UNHANDLED = "FRONTEND_UNHANDLED";
        public static final String NETWORK         = "NETWORK";
        public static final String TIMEOUT         = "TIMEOUT";
        public static final String INTERNAL        = "INTERNAL";

        // ── Tipos específicos del flujo conversacional del bot ──
        // BOT_EMPTY_RESPONSE: Anthropic devolvió 200 OK pero content vacío o
        //   stop_reason="error" (el usuario ve un mensaje sin texto del bot).
        //   No es un error HTTP — el log.warn lo dispara BotAiController cuando
        //   detecta el patrón en la response.
        // BOT_TOOL_FAILURE: una tool del bot falló al ejecutarse (validación de
        //   parámetros, SQL violando constraints, etc.). BotToolExecutorService
        //   las loguea ahora antes del re-throw.
        public static final String BOT_EMPTY_RESPONSE = "BOT_EMPTY_RESPONSE";
        public static final String BOT_TOOL_FAILURE   = "BOT_TOOL_FAILURE";

        private Types() {}
    }

    /**
     * Clasifica el error en un type. Nunca devuelve null — fallback es
     * INTERNAL para que el panel siempre tenga algo que mostrar.
     *
     * @param message   message del error (puede venir del exception, del
     *                  log event, o del frontend window.onerror)
     * @param stack     stack trace completo (opcional)
     * @param hint      hint opcional del caller. Valores típicos:
     *                  "fetch", "react", "unhandled", "exception_handler",
     *                  "logback". Si no matchea nada por contenido pero
     *                  el hint tiene sentido, lo usamos como tiebreaker.
     */
    public String classify(String message, String stack, String hint) {
        String haystack = combineAndLower(message, stack);
        String h = hint == null ? "" : hint.toLowerCase(Locale.ROOT);

        // ── Bot — tool failures ───────────────────────────────────────────
        // Patrón emitido por BotToolExecutorService.log.warn:
        //   "[BotToolExecutor] tool '<name>' falló (esperable): <msg>"
        // O por el log.error del catch genérico:
        //   "Error ejecutando tool <name>: <msg>"
        // Lo chequeamos antes que DATABASE porque las tools típicamente fallan
        // por motivos de DB (constraints), y queremos atribuir el error al bot,
        // no a la BD en general — facilita el filtrado del operador.
        if (containsAny(haystack,
                "[bottoolexecutor]",
                "error ejecutando tool",
                "toolexecutionexception")) {
            return Types.BOT_TOOL_FAILURE;
        }

        // ── Bot — respuestas vacías de Anthropic ──────────────────────────
        // Patrón emitido por BotAiController.log.warn cuando Anthropic devuelve
        // 200 OK pero con content=[] o stop_reason="error". Es un caso real que
        // confunde al usuario (ve un mensaje vacío del bot).
        if (containsAny(haystack,
                "anthropic devolvió respuesta sospechosa",
                "\"content\":[]",
                "\"content\": []",
                "\"stop_reason\":\"error\"")) {
            return Types.BOT_EMPTY_RESPONSE;
        }

        // ── Anthropic API ─────────────────────────────────────────────────
        // Comprobamos primero porque su error puede contener trozos genéricos
        // ("connection", "timeout") que matchearían otros buckets.
        if (containsAny(haystack,
                "api.anthropic.com",
                "anthropic api",
                "anthropicservice",
                "overloaded_error",
                "x-api-key",
                "claude-sonnet-",
                "claude-haiku-",
                "claude-opus-",
                "anthropic-version",
                "rate_limit_error")) {
            return Types.ANTHROPIC_API;
        }

        if (containsAny(haystack,
                "api.openai.com",
                "openai api",
                "openai.error")) {
            return Types.OPENAI_API;
        }

        if (containsAny(haystack,
                "api.elevenlabs.io",
                "elevenlabs")) {
            return Types.ELEVENLABS_API;
        }

        // ── Base de datos / pool ──────────────────────────────────────────
        // HikariPool deja huella inconfundible. También Spring SQLException
        // y JDBC4Connection. "Communications link failure" es el clásico de
        // MySQL cuando el pool perdió conexión.
        if (containsAny(haystack,
                "hikaripool",
                "hikaridatasource",
                "communications link failure",
                "could not get jdbc connection",
                "jdbc4connection",
                "sqlexception",
                "sql state",
                "datasource",
                "cannotgetjdbcconnection",
                "sqltransientconnectionexception",
                "connectionispermitted",
                "sqlnontransientconnection",
                "no operations allowed after connection",
                "communications exception",
                "data integrity violation",
                "constraintviolation",
                "duplicate entry",
                "deadlock found",
                "lock wait timeout")) {
            return Types.DATABASE;
        }

        // ── Flyway (migraciones) ──────────────────────────────────────────
        if (containsAny(haystack,
                "flyway",
                "flywayexception",
                "migration failed",
                "validate failed:")) {
            return Types.FLYWAY;
        }

        // ── Mail / SMTP ───────────────────────────────────────────────────
        if (containsAny(haystack,
                "javax.mail",
                "jakarta.mail",
                "mailsendexception",
                "messagingexception",
                "smtphost",
                "authenticationfailedexception",
                "could not connect to smtp")) {
            return Types.EMAIL_SMTP;
        }

        // ── Twilio (WhatsApp) ─────────────────────────────────────────────
        if (containsAny(haystack,
                "twilio",
                "api.twilio.com",
                "twiliorestclient")) {
            return Types.TWILIO;
        }

        // ── Auth / Seguridad ──────────────────────────────────────────────
        if (containsAny(haystack,
                "badcredentials",
                "jwt expired",
                "expiredjwt",
                "signatureexception",
                "malformedjwt",
                "accessdeniedexception",
                "authentication failed",
                "401 unauthorized",
                "403 forbidden",
                "invalid token")) {
            return Types.AUTH;
        }

        // ── Validation ────────────────────────────────────────────────────
        if (containsAny(haystack,
                "methodargumentnotvalid",
                "constraintviolationexception",
                "validationexception",
                "bindexception",
                "badrequestexception")) {
            return Types.VALIDATION;
        }

        if (containsAny(haystack,
                "notfoundexception",
                "404 not found",
                "no value present",
                "no such element")) {
            return Types.NOT_FOUND;
        }

        if (containsAny(haystack,
                "409 conflict",
                "conflictexception",
                "optimisticlock")) {
            return Types.CONFLICT;
        }

        // ── File IO / network ─────────────────────────────────────────────
        if (containsAny(haystack,
                "filenotfound",
                "ioexception",
                "no such file",
                "permission denied",
                "access denied")) {
            return Types.FILE_IO;
        }

        if (containsAny(haystack,
                "connection refused",
                "connection reset",
                "unknownhost",
                "sockettimeout",
                "connectexception",
                "no route to host",
                "network is unreachable")) {
            return Types.NETWORK;
        }

        if (containsAny(haystack,
                "timeout",
                "timed out",
                "read timed out",
                "deadlineexceeded")) {
            return Types.TIMEOUT;
        }

        // ── Frontend específicos ──────────────────────────────────────────
        if (h.contains("fetch") || containsAny(haystack,
                "failed to fetch",
                "network request failed",
                "typeerror: failed to fetch",
                "404 (not found)",
                "500 (internal server error)")) {
            return Types.FRONTEND_FETCH;
        }

        if (h.contains("react") || h.contains("render") || containsAny(haystack,
                "react error boundary",
                "minified react error",
                "cannot read properties of undefined",
                "cannot read property",
                "is not a function",
                "is undefined",
                "is not defined")) {
            return Types.FRONTEND_RENDER;
        }

        if (h.contains("unhandled") || h.contains("rejection")) {
            return Types.FRONTEND_UNHANDLED;
        }

        // ── Fallback ──────────────────────────────────────────────────────
        return Types.INTERNAL;
    }

    /**
     * Produce una descripción corta (1 línea) apta para listar en la tabla.
     * Si el message es vacío, usa el tipo como fallback. Detecta sub-patterns
     * comunes para mostrar algo más útil que el message crudo:
     *  - Reconexiones de DB → "Reconexión a base de datos (X)"
     *  - Rate limit Anthropic → "Anthropic rate-limited"
     *  - Etc.
     */
    public String shortDescription(String type, String message, String stack) {
        String msg = message == null ? "" : message.trim();
        String hay = combineAndLower(message, stack);

        if (Types.DATABASE.equals(type)) {
            if (hay.contains("communications link failure"))
                return "Reconexión perdida con MySQL (Communications link failure)";
            if (hay.contains("could not get jdbc connection"))
                return "Reconexión a base de datos (pool agotado)";
            if (hay.contains("duplicate entry"))
                return "Violación de UNIQUE — duplicate entry";
            if (hay.contains("deadlock found"))
                return "Deadlock en MySQL";
            if (hay.contains("lock wait timeout"))
                return "Lock wait timeout (>50s) en MySQL";
            if (hay.contains("data integrity"))
                return "Violación de integridad referencial / constraint";
            return firstLine(msg, "Error de base de datos");
        }

        if (Types.ANTHROPIC_API.equals(type)) {
            if (hay.contains("overloaded_error"))
                return "Anthropic sobrecargado (529 overloaded_error)";
            if (hay.contains("rate_limit"))
                return "Anthropic rate-limited (429)";
            if (hay.contains("authentication"))
                return "Anthropic auth error — API key inválida";
            if (hay.contains("invalid_request"))
                return "Anthropic invalid_request — payload inválido";
            return firstLine(msg, "Error en API de Anthropic");
        }

        if (Types.OPENAI_API.equals(type))     return firstLine(msg, "Error en API de OpenAI");
        if (Types.ELEVENLABS_API.equals(type)) return firstLine(msg, "Error en API de ElevenLabs");
        if (Types.EMAIL_SMTP.equals(type))     return firstLine(msg, "Error enviando email (SMTP)");
        if (Types.TWILIO.equals(type))         return firstLine(msg, "Error en Twilio (WhatsApp)");
        if (Types.AUTH.equals(type))           return firstLine(msg, "Error de autenticación / permisos");
        if (Types.VALIDATION.equals(type))     return firstLine(msg, "Error de validación");
        if (Types.NOT_FOUND.equals(type))      return firstLine(msg, "Recurso no encontrado");
        if (Types.CONFLICT.equals(type))       return firstLine(msg, "Conflicto al actualizar");
        if (Types.FLYWAY.equals(type))         return firstLine(msg, "Error de migración Flyway");
        if (Types.FILE_IO.equals(type))        return firstLine(msg, "Error de I/O de archivo");
        if (Types.NETWORK.equals(type))        return firstLine(msg, "Error de red");
        if (Types.TIMEOUT.equals(type))        return firstLine(msg, "Timeout");

        if (Types.FRONTEND_FETCH.equals(type))    return firstLine(msg, "Falló un fetch del frontend");
        if (Types.FRONTEND_RENDER.equals(type))   return firstLine(msg, "Error de render React");
        if (Types.FRONTEND_UNHANDLED.equals(type))return firstLine(msg, "Promesa rechazada sin handler");

        if (Types.BOT_EMPTY_RESPONSE.equals(type)) {
            if (hay.contains("\"stop_reason\":\"error\""))
                return "Bot devolvió respuesta vacía (stop_reason=error)";
            if (hay.contains("\"content\":[]") || hay.contains("\"content\": []"))
                return "Bot devolvió respuesta vacía (content=[])";
            return firstLine(msg, "Bot devolvió respuesta vacía o anómala");
        }

        if (Types.BOT_TOOL_FAILURE.equals(type)) {
            // Tratar de extraer el nombre de la tool del mensaje. Patrón típico:
            //   "tool '<name>' falló" o "Error ejecutando tool <name>:"
            String toolName = extractToolName(msg);
            if (toolName != null) return "Tool '" + toolName + "' del bot falló";
            return firstLine(msg, "Tool del bot falló");
        }

        return firstLine(msg, "Error interno");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String combineAndLower(String message, String stack) {
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message);
        if (stack != null) {
            if (sb.length() > 0) sb.append(' ');
            // Solo los primeros 4KB del stack para no inflar el matching y
            // mantener performance — los patterns relevantes aparecen ahí.
            sb.append(stack, 0, Math.min(stack.length(), 4096));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /** Devuelve la primera línea del message acotada a 200 caracteres. */
    private String firstLine(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        line = line.trim();
        if (line.length() > 200) line = line.substring(0, 197) + "...";
        return line.isEmpty() ? fallback : line;
    }

    /**
     * Extrae el nombre de la tool de un mensaje de error típico.
     * Patrones soportados:
     *   "[BotToolExecutor] tool 'add_record' falló (esperable): ..."
     *   "Error ejecutando tool add_record: ..."
     * Devuelve null si no se puede extraer.
     */
    private String extractToolName(String msg) {
        if (msg == null) return null;
        // Patrón 1: tool '<name>'
        int q1 = msg.indexOf("tool '");
        if (q1 >= 0) {
            int q2 = msg.indexOf("'", q1 + 6);
            if (q2 > q1 + 6) return msg.substring(q1 + 6, q2);
        }
        // Patrón 2: "ejecutando tool <name>:"
        int idx = msg.indexOf("ejecutando tool ");
        if (idx >= 0) {
            int start = idx + "ejecutando tool ".length();
            int end = msg.indexOf(':', start);
            if (end > start) return msg.substring(start, end).trim();
            // sin ':' — tomamos hasta espacio
            int sp = msg.indexOf(' ', start);
            if (sp > start) return msg.substring(start, sp).trim();
        }
        return null;
    }
}
