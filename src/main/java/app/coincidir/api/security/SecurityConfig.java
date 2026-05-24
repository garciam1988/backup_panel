package app.coincidir.api.security;

import app.coincidir.api.tenancy.filter.BranchResolverFilter;
import app.coincidir.api.tenancy.service.BranchResolverService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RequestIdFilter requestIdFilter;
    private final BranchResolverService branchResolverService;
    private final DiosOrAdminFilter diosOrAdminFilter;
    private final RateLimitFilter rateLimitFilter;

    /**
     * BranchResolverFilter como @Bean (no @Component) para que Spring Boot
     * NO lo agregue automáticamente al chain — lo registramos abajo a mano
     * con addFilterAfter para garantizar que corre justo después del JWT.
     */
    @Bean
    BranchResolverFilter branchResolverFilter() {
        return new BranchResolverFilter(branchResolverService);
    }

    /**
     * Deshabilita el registro automático de DiosOrAdminFilter en el chain
     * global de servlets. Sino correría DOS veces: una via @Component que
     * Spring Boot auto-registra, y otra via addFilterAfter abajo en el
     * SecurityFilterChain. Acá decimos: "no lo agregues solo, ya yo lo
     * agrego donde quiero (después del BranchResolverFilter)".
     */
    @Bean
    FilterRegistrationBean<DiosOrAdminFilter> disableDiosOrAdminGlobalRegistration(
            DiosOrAdminFilter filter) {
        FilterRegistrationBean<DiosOrAdminFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults()) // usa el bean CORS
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // preflight
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // logins públicos
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/user/auth/login").permitAll()

                        // alias /api/web/** (para módulo de monitoreo)
                        .requestMatchers(HttpMethod.POST, "/api/web/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/web/user/auth/login").permitAll()

                        // endpoints públicos
                        .requestMatchers(HttpMethod.POST, "/api/send-email").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/user/group/me").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/requests").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/config/destinations").permitAll()

                        // client logs (frontend)
                        .requestMatchers(HttpMethod.POST, "/api/client-logs").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/client-logs").authenticated()

                        .requestMatchers(HttpMethod.POST,"/api/coinbot/**").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/coinbot/**").permitAll()

                        // ── Endpoints públicos del bot frontend (i18n, idiomas) ─
                        // El bot es accesible por visitantes anónimos, así que estos
                        // endpoints no requieren autenticación. GET para data + POST
                        // para translate-custom (traducción de textos custom del bot
                        // al idioma del cliente, sin persistir).
                        .requestMatchers(HttpMethod.GET,  "/api/bot/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/bot/translate-custom").permitAll()

                        // ── Endpoints /api/public/** ──
                        // Lecturas que el bot público necesita: bot-config, prompt
                        // templates activos, data sources prompt-context, menu-images,
                        // bot-table-tools/prompt-context, etc. NO escriben nada y
                        // exponen DTOs acotados (sin metadata sensible).
                        .requestMatchers(HttpMethod.GET,  "/api/public/**").permitAll()

                        // ── BotTools públicas para el CoinBot del cliente final ──
                        // El GET /for-bot ya cae bajo `GET /api/public/**` de arriba.
                        // El POST /execute lo declaramos explícito para que el
                        // SecurityConfig sea autodocumentado y consistente con
                        // los otros Public*Controller del bot.
                        .requestMatchers(HttpMethod.POST, "/api/public/bot-tools/execute").permitAll()

                        // ── Marketing/Loyalty: PWA pública del cliente ──
                        // La PWA usa el customer_hash en la URL como bearer
                        // alternativo; no requiere JWT panel. Permitimos POST
                        // públicos sobre /api/public/loyalty/** (enroll,
                        // redeem-request, push-subscription, etc) y sobre
                        // /api/public/loyalty-tools/** (tools que el bot
                        // conversacional consume, mismo patrón que
                        // /api/public/bot-table-tools y /api/public/bot-api-tools).
                        .requestMatchers("/api/public/loyalty/**").permitAll()
                        .requestMatchers("/api/public/loyalty-tools/**").permitAll()

                        // ── Staff App (mozos / cajeros operando en el local) ──
                        // El login con PIN es público; el resto requiere JWT
                        // staff (mismo filtro, ver JwtAuthFilter). Las reglas
                        // de autorización por rol no se aplican acá; quedan a
                        // nivel de service (StaffLoyaltyController valida el
                        // staff_user_id del JWT al operar).
                        .requestMatchers(HttpMethod.POST, "/api/staff-app/auth/login").permitAll()
                        .requestMatchers("/api/staff-app/**").authenticated()

                        // swaggerr
                        .requestMatchers(
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
                        ).permitAll()

                        // DEV utils
                        .requestMatchers("/dev/**").permitAll()

                        // Admin Panel: requiere token para todo lo /api/admin/** excepto login.
                        // (No forzamos rol para no romper flujos actuales.)
                        .requestMatchers("/api/admin/**").authenticated()

                        // Alias Admin Panel: requiere token para todo lo /api/web/admin/** excepto login.
                        .requestMatchers("/api/web/admin/**").authenticated()

                        // Manager module (ARViz / Jarvis): requiere JWT.
                        // El controller además valida user_account.manager_access=true.
                        .requestMatchers("/api/manager/**").authenticated()
                        .requestMatchers("/api/me/**").authenticated()

                        // el resto queda abierto como antes
                        .anyRequest().permitAll()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) -> {
                            writeAuthError(response, 401, "Unauthorized", "Sesión inválida o expirada", request.getRequestURI(),
                                    response.getHeader("X-Request-Id"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            writeAuthError(response, 403, "Forbidden", "Acceso denegado", request.getRequestURI(),
                                    response.getHeader("X-Request-Id"));
                        })
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // BranchResolverFilter va DESPUÉS del JWT para tener la auth ya
                // resuelta — útil cuando agreguemos user_branch_access (bloque 7).
                // Hoy no usa el principal, pero el orden ya queda preparado.
                .addFilterAfter(branchResolverFilter(), JwtAuthFilter.class)
                // DiosOrAdminFilter: bloquea endpoints de config global del bot
                // (prompt, reglas, identidad, api keys, tools, marketing, etc)
                // a usuarios que NO sean DIOS o ADMIN. Va después de BranchResolver
                // para que el log de denegación pueda incluir el contexto del user
                // si quisiéramos. Lista de paths protegidos en la propia clase.
                .addFilterAfter(diosOrAdminFilter, BranchResolverFilter.class);

        return http.build();
    }

    private void writeAuthError(HttpServletResponse response,
                                int status,
                                String error,
                                String message,
                                String path,
                                String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        if (requestId != null && !requestId.isBlank()) body.put("requestId", requestId);

        // Serialización mínima sin dependencias extra
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : body.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
            sb.append('"').append(escapeJson(String.valueOf(e.getValue()))).append('"');
        }
        sb.append('}');
        response.getWriter().write(sb.toString());
    }

    private String escapeJson(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // fuerza BCrypt sin prefijos
    }

    /*
    @Bean
    CommandLineRunner printHash() {
        return args -> {
            System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("123456"));
        };
    }
    */

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Permite cualquier puerto local (ej: 3000, 3003, etc.)
        cfg.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*.ngrok.app",
                "https://*.ngrok-free.app",
                "https://*.ngrok.io",
                "https://*.coincidir-travel.com",
                // Capacitor Android
                "capacitor://localhost",
                // Capacitor iOS
                "https://localhost",
                "ionic://localhost",
                "https://*.railway.app",
                "https://*.yes-traveluy.com",
                "https://arviz-solutions.com",
                // El patrón `*.arviz-solutions.com` cubre subdominios de UN
                // solo nivel (ej: bot.arviz-solutions.com). En CORS / RFC 6125,
                // un asterisco NO matchea puntos: NO cubre `foo.bar.arviz-solutions.com`.
                "https://*.arviz-solutions.com",
                // Para clientes que tienen 2 niveles de subdominio
                // (ej: preproduction.brasas-argentinas.arviz-solutions.com,
                //      staging.brasas-argentinas.arviz-solutions.com),
                // agregamos un patrón específico por marca. NO usamos un wildcard
                // universal del estilo `*.*.arviz-solutions.com` porque ampliaría
                // demasiado la superficie de ataque (cualquier subdominio anidado
                // tendría acceso a la API).
                "https://*.brasas-argentinas.arviz-solutions.com"
        ));
        cfg.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        // X-Branch-Id, X-Brand-Slug, X-Branch-Slug → headers de tenancy.
        // X-Branch-All → modo cross-branch (solo DIOS, lectura de todas las sucursales).
        // X-Session-Id → identificador de sesión del chat AI, usado por el rate
        //                limit por sessionId en BotAiController.
        // Sin estos en la lista, el browser hace preflight OK (200) pero
        // bloquea el fetch real por CORS — síntoma: errores "CORS error"
        // en Network y respuestas vacías en el admin.
        cfg.setAllowedHeaders(List.of(
            "Authorization","Content-Type","Accept","Origin","X-Requested-With","X-Request-Id",
            "X-Branch-Id","X-Brand-Slug","X-Branch-Slug","X-Branch-All",
            "X-Session-Id"
        ));
        cfg.setExposedHeaders(List.of("Authorization","X-Request-Id")); // si expones el token
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}

