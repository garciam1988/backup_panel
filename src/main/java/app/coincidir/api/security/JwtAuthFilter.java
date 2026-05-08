package app.coincidir.api.security;

import app.coincidir.api.repository.PanelUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JwtAuthFilter — autentica peticiones a partir del header Authorization: Bearer ....
 *
 * Resuelve el subject del JWT contra PanelUser (sistema unificado de roles).
 *
 * NOTA: Originalmente este filtro también validaba contra UserAccount (legacy
 * de Coincidir). Ese fallback fue eliminado para que el sistema de admin sea
 * 100% gobernado por PanelUser/AppRole. Los UserAccount conservan sus propios
 * controllers de auth (/api/user/auth/login, /api/coinbot, etc.) que NO usan
 * este filtro o lo usan a través de otro flujo.
 *
 * Si el subject no matchea ningún PanelUser, se sigue sin autenticación (para
 * que los endpoints permitAll() del SecurityConfig funcionen).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final PanelUserRepository panelUserRepo;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String authS = request.getHeader("Authorization");
        if (authS == null || !authS.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authS.substring(7);
        try {
            var jws = jwtService.parse(token);
            Claims claims = jws.getPayload();
            String subject = claims.getSubject();
            String role = claims.get("role", String.class);

            // Si ya hay auth en el contexto (ej: otro filtro), no la pisamos
            if (SecurityContextHolder.getContext().getAuthentication() == null && subject != null) {
                // Sólo PanelUser. UserAccount tiene sus propios flujos de auth.
                if (panelUserRepo.findByUsername(subject).isPresent()) {
                    Authentication auth = buildAuth(subject, role);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }

        } catch (JwtException | IllegalArgumentException ignored) {
            // token inválido/expirado → seguimos sin autenticación
        }

        chain.doFilter(request, response);
    }

    private Authentication buildAuth(String subject, String role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase()));
        }
        return new UsernamePasswordAuthenticationToken(subject, null, authorities);
    }
}
