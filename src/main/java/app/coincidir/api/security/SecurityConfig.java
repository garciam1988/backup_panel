package app.coincidir.api.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
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
                .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
                "https://*.arviz-solutions.com"
                

        ));
        cfg.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization","Content-Type","Accept","Origin","X-Requested-With","X-Request-Id"));
        cfg.setExposedHeaders(List.of("Authorization","X-Request-Id")); // si expones el token
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}

