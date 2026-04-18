package app.coincidir.api.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Alias de endpoints para el módulo de monitoreo.
 *
 * Permite consumir los mismos endpoints bajo el prefijo /api/web/**,
 * manteniendo intactas las rutas reales (/api/admin, /api/operations, /api/conciliation, /api/user, etc.).
 *
 * Ejemplo:
 *  - /api/web/operations/...  -> forward interno a /api/operations/...
 *  - /api/web/admin/...       -> forward interno a /api/admin/...
 */
@RestController
public class WebAliasProxyController {

    @RequestMapping(value = "/api/web/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        final String uri = request.getRequestURI() == null ? "" : request.getRequestURI();

        String path = uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
        if (!path.startsWith("/api/web/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // /api/web/<resto> -> /api/<resto>
        String target = "/api/" + path.substring("/api/web/".length());

        // Evita loops accidentales
        if (target.startsWith("/api/web/")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid alias target");
            return;
        }

        RequestDispatcher dispatcher = request.getRequestDispatcher(target);
        dispatcher.forward(request, response);
    }
}
