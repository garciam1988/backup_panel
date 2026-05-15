package app.coincidir.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reescribe rutas del OpenAPI para exponerlas bajo /api/web/*.
 *
 * Esto permite que el módulo de monitoreo agrupe por sector (Admin/Operations/Check/User)
 * sin cambiar las rutas reales consumidas por el resto de los paneles.
 */
@Configuration
public class OpenApiWebAliasCustomizer {

    @Bean
    public OpenApiCustomizer webAliasPathsCustomizer() {
        return (OpenAPI openApi) -> {
            if (openApi == null || openApi.getPaths() == null) return;

            Paths updated = new Paths();
            openApi.getPaths().forEach((path, item) -> {
                String newPath = mapPath(path);
                updated.addPathItem(newPath, item);
            });

            openApi.setPaths(updated);
        };
    }

    private String mapPath(String path) {
        if (path == null) return null;
        if (path.startsWith("/api/admin")) {
            return path.replaceFirst("^/api/admin", "/api/web/admin");
        }
        if (path.startsWith("/api/operations")) {
            return path.replaceFirst("^/api/operations", "/api/web/operations");
        }
        if (path.startsWith("/api/conciliation")) {
            return path.replaceFirst("^/api/conciliation", "/api/web/conciliation");
        }
        if (path.startsWith("/api/user")) {
            return path.replaceFirst("^/api/user", "/api/web/user");
        }
        return path;
    }
}
