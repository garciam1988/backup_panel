package app.coincidir.api.errormonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ErrorMonitorLogbackAppender — appender programático que captura todos los
 * eventos WARN+ del logger raíz del backend y los manda al ErrorMonitorService
 * para que queden persistidos en la tabla client_log_event.
 *
 * Esto permite ver en /admin → Monitor de errores cosas que NO pasaron por
 * el ApiExceptionHandler (porque no rompieron un request) pero que igual son
 * útiles para diagnóstico:
 *   - HikariCP advirtiendo que una conexión tardó en establecerse
 *   - Anthropic devolviendo 529 que el caller decidió reintentar
 *   - Hibernate avisando de un connection drop transparente
 *   - Job nocturno que falló pero el log siguió
 *
 * Por qué un appender programático en vez de declarativo en logback-spring.xml:
 *  - Necesitamos inyectar el ErrorMonitorService (Spring bean).
 *  - Tenemos un kill-switch en runtime (enabled flag del service).
 *  - Podemos filtrar por loggerName fácilmente (evitar loops).
 *
 * Auto-registro vía @PostConstruct: cuando Spring inicializa este bean, nos
 * enganchamos al ROOT logger de Logback. Al destruir, nos desenganchamos
 * (importante para tests y para hot-reload en dev).
 *
 * IMPORTANTE: usamos AtomicBoolean recursionGuard para no entrar en loops
 * infinitos si el propio service tiene un error que loguea. Si el guard ya
 * está set, hacemos drop silencioso.
 */
@Component
@RequiredArgsConstructor
public class ErrorMonitorLogbackAppender extends AppenderBase<ILoggingEvent> {

    private final ErrorMonitorService errorMonitorService;

    /**
     * Capturamos WARN por default; puede subirse a ERROR si en producción
     * hay demasiado WARN ruidoso (Hibernate, MyBatis, etc.). Configurable
     * por property: coincidir.error-monitor.min-level (default WARN).
     */
    @Value("${coincidir.error-monitor.min-level:WARN}")
    private String minLevelStr;

    /**
     * Flag para deshabilitar la captura del logback sin tener que desactivar
     * todo el módulo. Útil si en algún momento el appender genera mucho ruido
     * y querés solo capturar errores del ApiExceptionHandler.
     */
    @Value("${coincidir.error-monitor.capture-logback:true}")
    private boolean captureLogback;

    private Level minLevel;
    private final AtomicBoolean recursionGuard = new AtomicBoolean(false);
    private final AtomicLong droppedDueToRecursion = new AtomicLong(0);

    /**
     * Loggers ruidosos que sabemos que generan WARNs informativos no-accionables.
     * Estos mensajes NO indican un problema real — son avisos de arranque, deprecation
     * warnings de Hibernate, etc. Si los capturáramos inundarían el monitor con falsos
     * positivos. Se filtran ANTES de llegar al ErrorMonitorService.
     *
     * Si en algún momento querés capturarlos (debug profundo), comentá la lista o
     * subí el min-level a ERROR (lo que descarta todos los WARN automáticamente).
     */
    private static final List<String> NOISY_LOGGERS = List.of(
        // Password auto-generada al arranque (solo aparece si Security Starter está activo
        // sin user/pass configurado — caso típico cuando hay JWT propio, como nuestro setup).
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        // Aviso de "open in view" deprecado de Hibernate (no es un bug, es config).
        "org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration$JpaWebConfiguration",
        // Deprecations de Hibernate ORM al arrancar (no afectan runtime).
        "org.hibernate.orm.deprecation",
        // Bean info de WebMvcAutoConfiguration cuando hay handlers custom (ruido OK).
        "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping"
    );

    /**
     * Substrings de mensajes específicos que también querés filtrar aunque vengan
     * de loggers no listados arriba. Útil para mensajes genéricos que aparecen en
     * múltiples loggers (ej. el aviso de password autogenerada también podría salir
     * de un logger diferente según versión de Spring Boot).
     */
    private static final List<String> NOISY_MESSAGE_SUBSTRINGS = List.of(
        "Using generated security password",
        "This generated password is for development use only"
    );

    @PostConstruct
    public void start() {
        this.minLevel = parseLevel(minLevelStr);
        // Nombre del appender para identificarlo en jconsole / debug.
        setName("ErrorMonitorAppender");

        // Necesitamos el LoggerContext de Logback (que ya está corriendo).
        // SLF4J devuelve ILoggerFactory que en runtime es ch.qos.logback.classic.LoggerContext.
        org.slf4j.ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();
        if (!(iLoggerFactory instanceof ch.qos.logback.classic.LoggerContext ctx)) {
            // No estamos corriendo Logback (raro pero defensivo) → no hacemos nada.
            return;
        }

        // Vinculamos el contexto al appender (necesario para que start() no falle
        // con NullPointerException buscando el StatusManager).
        setContext(ctx);
        super.start();

        if (captureLogback) {
            ch.qos.logback.classic.Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.addAppender(this);
        }
    }

    @PreDestroy
    public void stop() {
        try {
            org.slf4j.ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();
            if (iLoggerFactory instanceof ch.qos.logback.classic.LoggerContext ctx) {
                ch.qos.logback.classic.Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                root.detachAppender(this);
            }
            super.stop();
        } catch (Exception ignored) {}
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!captureLogback) return;
        if (event == null) return;

        // Filtro por level mínimo
        if (event.getLevel().toInt() < minLevel.toInt()) return;

        // Evitar loops: si ya estamos dentro de un append (recursivo porque
        // ErrorMonitorService loguea algo que vuelve acá), tragamos.
        if (!recursionGuard.compareAndSet(false, true)) {
            droppedDueToRecursion.incrementAndGet();
            return;
        }
        try {
            // Filtramos eventos del propio módulo para no auto-amplificarse.
            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("app.coincidir.api.errormonitor")) {
                return;
            }
            // También filtramos eventos del propio Logback (status manager).
            if (loggerName != null && loggerName.startsWith("ch.qos.logback")) {
                return;
            }

            // ── Filtro de loggers ruidosos conocidos ──
            // Estos generan WARNs informativos no-accionables (passwords auto-generadas,
            // deprecations, avisos de arranque). NO son bugs, no hace falta verlos en
            // el monitor.
            if (loggerName != null) {
                for (String noisy : NOISY_LOGGERS) {
                    if (loggerName.equals(noisy) || loggerName.startsWith(noisy + "$")) {
                        return;
                    }
                }
            }

            String message = safeFormat(event);

            // ── Filtro de mensajes ruidosos por contenido ──
            // Para casos donde el mismo mensaje puede venir de varios loggers según
            // versión de Spring Boot o configuración.
            if (message != null) {
                for (String substr : NOISY_MESSAGE_SUBSTRINGS) {
                    if (message.contains(substr)) {
                        return;
                    }
                }
            }

            String stack = stackFromEvent(event);
            String level = event.getLevel().toString().toLowerCase();
            String threadName = event.getThreadName();

            // Delegamos al service que es @Async — no bloqueamos el thread
            // que está logueando. Si el service muere, no impacta.
            errorMonitorService.captureSystemEvent(level, loggerName, message, stack, threadName);

        } catch (Throwable t) {
            // NUNCA propagar nada al thread que está logueando. Esto es crítico.
            // Si propagamos, podemos romper transacciones, requests, etc.
        } finally {
            recursionGuard.set(false);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Level parseLevel(String s) {
        if (s == null) return Level.WARN;
        try {
            Level l = Level.toLevel(s.trim().toUpperCase());
            // toLevel cae a DEBUG si no parsea — fuerzo WARN como seguro.
            return l == null ? Level.WARN : l;
        } catch (Exception e) {
            return Level.WARN;
        }
    }

    private String safeFormat(ILoggingEvent event) {
        try {
            return event.getFormattedMessage();
        } catch (Exception e) {
            return event.getMessage();
        }
    }

    private String stackFromEvent(ILoggingEvent event) {
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) return null;
        try {
            return ThrowableProxyUtil.asString(tp);
        } catch (Exception e) {
            return tp.getClassName() + ": " + tp.getMessage();
        }
    }
}
