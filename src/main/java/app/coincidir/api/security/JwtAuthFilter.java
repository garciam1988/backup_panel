package app.coincidir.api.security;

import app.coincidir.api.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserAccountRepository userRepo;

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
            String email = claims.getSubject();
            String role = claims.get("role", String.class);

            userRepo.findByEmail(email).ifPresent(user -> {
                GrantedAuthority authority =
                        new SimpleGrantedAuthority("ROLE_" + role);
                Authentication auth =
                        new UsernamePasswordAuthenticationToken(
                                email, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });

        } catch (JwtException | IllegalArgumentException ignored) {
            // token inválido/expirado → seguimos sin autenticación
        }

        chain.doFilter(request, response);
    }


}
