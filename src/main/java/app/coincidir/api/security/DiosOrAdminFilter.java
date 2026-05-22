package app.coincidir.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * DiosOrAdminFilter — fuerza que ciertos paths solo respondan a DIOS o ADMIN.
 *
 * Modelo de seguridad de configuración global:
 *   - Frontend: oculta los menús de config global a roles operativos.
 *   - Backend (este filtro): rechaza la llamada API aunque el usuario la
 *     invoque a mano con curl/postman/etc.
 *
 * Esto NO es un filtro de "autenticación" — Spring Security ya autentica con
 * JWT antes de llegar acá. Este filtro corre DESPUÉS del JwtAuthFilter y solo
 * chequea autorización por rol contra la lista de paths protegidos.
 *
 * Por qué un filtro y no un AOP/Interceptor en cada controller:
 *   - Centralizado: la lista de paths protegidos vive en un solo lugar.
 *   - Cero invasivo: los controllers no se enteran, no hay riesgo de
 *     "se me olvidó poner el @PreAuthorize en este endpoint nuevo".
 *   - Fácil de auditar: leés esta clase y sabés qué está protegido.
 *
 * Lista de paths protegidos (config global del bot):
 *   - /api/admin/bot-config/**           → settings generales del bot
 *   - /api/admin/bot-prompt-templates/** → plantillas y prompt activo
 *   - /api/admin/bot-tools/**            → tools SQL que Claude puede invocar
 *   - /api/admin/bot-api-tools/**        → integraciones API
 *   - /api/admin/bot-table-tools/**      → tablas custom del bot (admin)
 *   - /api/admin/bot-connectors/**       → bases de datos externas
 *   - /api/admin/api-keys/**             → credenciales (Anthropic, OpenAI, etc)
 *   - /api/admin/backups/**              → snapshots de config
 *   - /api/admin/marketing/**            → campañas, segmentos, programa de lealtad
 *
 * Lo que NO está acá (intencionalmente, por ahora):
 *   - /api/admin/tenancy/branches/**     → ya validado por canManageUsers internamente
 *   - /api/admin/panel-users/**          → ya validado por canManageUsers
 *   - /api/admin/audit-log/**            → solo lectura, no requiere DIOS/ADMIN
 *   - Endpoints de data por-sucursal (conversations, clients, smart tables)
 *     → no son globales
 *
 * Si querés agregar más paths protegidos, ampliá la lista PROTECTED_PATHS.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiosOrAdminFilter extends OncePerRequestFilter {

    private final PermissionsService permissionsService;

    /**
     * Paths que requieren DIOS o ADMIN. Se chequean con startsWith.
     * Mantenelos en orden alfabético para facilitar reviews.
     */
    private static final List<String> PROTECTED_PATHS = List.of(
            "/api/admin/api-keys",
            "/api/admin/backups",
            "/api/admin/bot-api-tools",
            "/api/admin/bot-config",
            "/api/admin/bot-connectors",
            "/api/admin/bot-prompt-templates",
            "/api/admin/bot-table-tools",
            "/api/admin/bot-tools",
            "/api/admin/marketing"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();

        // ── Skip rápido si el path NO está protegido ──────────────────────
        if (!isProtected(path)) {
            chain.doFilter(req, res);
            return;
        }

        // ── Skip preflight CORS (lo maneja el CorsFilter, no acá) ─────────
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        // ── Verificar rol del usuario autenticado ──────────────────────────
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            // Spring Security ya debería haber bloqueado, pero por las dudas
            res.sendError(HttpStatus.UNAUTHORIZED.value(),
                    "Autenticación requerida para configuración global");
            return;
        }

        String username = auth.getName();
        try {
            permissionsService.requireDiosOrAdmin(username);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            log.warn("[DiosOrAdminFilter] Acceso denegado a {}: {} (user='{}')",
                    path, e.getStatusCode().value(), username);
            res.sendError(e.getStatusCode().value(), e.getReason());
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isProtected(String path) {
        if (path == null) return false;
        for (String prefix : PROTECTED_PATHS) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
