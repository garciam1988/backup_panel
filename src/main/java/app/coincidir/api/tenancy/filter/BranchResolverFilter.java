package app.coincidir.api.tenancy.filter;

import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import app.coincidir.api.tenancy.service.BranchResolverService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * BranchResolverFilter — resuelve la sucursal del request y la pone en el
 * {@link BranchContext} para que el resto del stack la consuma.
 *
 * Se registra en SecurityConfig con `.addFilterAfter(branchResolverFilter,
 * JwtAuthFilter.class)` para garantizar que corre DESPUÉS del JWT — así
 * podemos leer los claims `branchIds`, `allBranches` y `preferredBranchId`
 * que JwtAuthFilter expone como request attributes.
 *
 * IMPORTANTE: este filter NO tiene @Component a propósito. Se registra como
 * @Bean en SecurityConfig para evitar que Spring Boot lo agregue dos veces.
 *
 * ── Cadena de resolución (gana el primer match válido) ──────────────────
 *
 *   1. Header X-Branch-Id explícito (el frontend del admin lo manda cuando
 *      el usuario eligió una sucursal del selector — DIOS o user multi-branch).
 *      Si el header se manda pero el user NO tiene acceso a esa branch, se
 *      ignora con warning (NO se rechaza el request — el filter no es lugar
 *      para 403; el endpoint decidirá si hace falta).
 *
 *   2. Claim `preferredBranchId` del JWT (la branch "preferida" del user no-DIOS
 *      que se determinó al loguear).
 *
 *   3. Primer branch del claim `branchIds` (caso usuario no-DIOS sin preferida).
 *
 *   4. Header X-Branch-Slug + X-Brand-Slug (bot público, sin JWT).
 *
 *   5. Default de la marca única — fallback final, cubre TODOS los requests
 *      legacy y los del bot público sin slugs.
 *
 * Nunca devuelve null para users autenticados que tienen branches: siempre
 * llega a algo razonable. Si todo falla, el scope queda null y los endpoints
 * que requieran tenancy van a tirar IllegalStateException explícita.
 *
 * Después de procesar el request, el filtro SIEMPRE limpia el ThreadLocal
 * para evitar leaks entre requests del mismo thread del pool de Tomcat.
 */
@Slf4j
@RequiredArgsConstructor
public class BranchResolverFilter extends OncePerRequestFilter implements Ordered {

    /** Ejecutar después de JwtAuthFilter. Order alto = más tarde en la cadena. */
    public static final int FILTER_ORDER = 200;

    private final BranchResolverService resolver;

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        try {
            BranchScope scope = resolveScope(req).orElse(null);
            BranchContext.set(scope);

            if (scope != null && log.isTraceEnabled()) {
                log.trace("[BranchResolver] {} {} -> brand={} branch={}",
                        req.getMethod(), req.getRequestURI(),
                        scope.getBrandSlug(), scope.getBranchSlug());
            }

            chain.doFilter(req, res);
        } finally {
            // CRÍTICO: limpiar siempre, aun con excepción. El thread vuelve
            // al pool de Tomcat y se reusa para otros requests.
            BranchContext.clear();
        }
    }

    /** Aplica la cadena de resolución descrita en el Javadoc de la clase. */
    private Optional<BranchScope> resolveScope(HttpServletRequest req) {

        // ── Info de tenancy que JwtAuthFilter ya extrajo del token ────────
        Boolean allBranches = (Boolean) req.getAttribute("jwt.allBranches");
        List<Long> userBranchIds = extractBranchIds(req.getAttribute("jwt.branchIds"));
        Long preferredBranchId = extractLong(req.getAttribute("jwt.preferredBranchId"));

        // ── 0) Header X-Branch-All: modo cross-branch (solo DIOS) ─────────
        //
        // DIOS puede pedir "ver todas las sucursales" mandando X-Branch-All: true.
        // En ese caso devolvemos Optional.empty() — el filter no setea scope,
        // y BranchContext.current() devuelve null. Las queries *ByBranchId
        // del service caen a la rama "fallback sin scope" que ya las trataba
        // como cross-branch (legacy: el código viejo antes del Bloque 3
        // funcionaba así).
        //
        // Para users no-DIOS, este header se IGNORA con warning. Es defensivo:
        // si por algún bug del frontend llegara, no queremos abrir la puerta
        // a ver data de otras branches a alguien sin permisos.
        //
        // Importante: este modo deshabilita escrituras branch-scoped en el
        // service (doAdd, doUpdate, doDelete validan scope!=null antes de
        // operar). Si se intenta escribir en modo all-branches, devuelve error.
        String allHeader = req.getHeader("X-Branch-All");
        if (allHeader != null && allHeader.equalsIgnoreCase("true")) {
            if (Boolean.TRUE.equals(allBranches)) {
                log.trace("[BranchResolver] modo cross-branch activado por DIOS");
                BranchContext.setCrossBranch(true);
                return Optional.empty();
            } else {
                log.warn("[BranchResolver] User no-DIOS intentó X-Branch-All=true — ignorado");
            }
        }

        // ── 1) Header X-Branch-Id explícito ───────────────────────────────
        // Para requests autenticados (admin), validamos que el user tenga
        // acceso a esa branch (o sea DIOS) — defensa contra clientes que se
        // inventen un branchId que no les corresponde.
        //
        // Para requests SIN JWT (bot público en endpoints permitAll como
        // /api/public/bot-table-tools/execute o /api/coinbot/**), no hay
        // claims contra los cuales validar: el id ya lo eligió la tool
        // `identificar_sucursal` del bot contra la marca configurada, así
        // que confiamos en el header. Sin esta excepción, el bot insertaba
        // records con branch_id=NULL y solo DIOS los veía (regresión
        // introducida en mayo 2026 al eliminar el fallback default_for_brand
        // sin contemplar que `hasAccess` también filtraba al bot público).
        String branchIdHeader = req.getHeader("X-Branch-Id");
        if (branchIdHeader != null && !branchIdHeader.isBlank()) {
            try {
                Long branchId = Long.parseLong(branchIdHeader.trim());
                boolean hasJwtClaims = (allBranches != null)
                        || !userBranchIds.isEmpty()
                        || preferredBranchId != null;
                boolean hasAccess = !hasJwtClaims                       // bot público sin JWT → confiamos
                        || Boolean.TRUE.equals(allBranches)             // DIOS
                        || userBranchIds.contains(branchId);            // user con esa branch asignada
                if (hasAccess) {
                    Optional<BranchScope> byId = resolver.resolveByBranchId(branchId);
                    if (byId.isPresent()) return byId;
                    log.warn("[BranchResolver] X-Branch-Id={} no encontrado o inactivo", branchId);
                } else {
                    log.warn("[BranchResolver] User intentó X-Branch-Id={} sin acceso — ignorado", branchId);
                }
            } catch (NumberFormatException nfe) {
                log.warn("[BranchResolver] X-Branch-Id inválido: '{}'", branchIdHeader);
            }
        }

        // ── 2) Preferida del JWT (user no-DIOS con branch preferida) ──────
        if (preferredBranchId != null) {
            Optional<BranchScope> pref = resolver.resolveByBranchId(preferredBranchId);
            if (pref.isPresent()) return pref;
            log.warn("[BranchResolver] preferredBranchId={} del JWT no resolvió", preferredBranchId);
        }

        // ── 3) Primera branch del JWT (user no-DIOS sin preferida) ────────
        if (!userBranchIds.isEmpty()) {
            Long first = userBranchIds.get(0);
            Optional<BranchScope> any = resolver.resolveByBranchId(first);
            if (any.isPresent()) return any;
        }

        // ── 4) Slugs (bot público sin JWT) ───────────────────────────────
        String brandSlug = req.getHeader("X-Brand-Slug");
        String branchSlug = req.getHeader("X-Branch-Slug");
        if ((brandSlug != null && !brandSlug.isBlank())
                || (branchSlug != null && !branchSlug.isBlank())) {
            Optional<BranchScope> bySlug = resolver.resolveBySlugs(brandSlug, branchSlug);
            if (bySlug.isPresent()) return bySlug;
            log.warn("[BranchResolver] slugs (brand={}, branch={}) no resolvieron",
                    brandSlug, branchSlug);
        }

        // Sin más fallbacks. Si llegamos acá, el request NO tiene branch
        // resoluble y el scope queda null. Los endpoints que requieren branch
        // van a fallar con error claro ("no hay sucursal en el contexto") en
        // vez de operar silenciosamente sobre una "default" — eso es a
        // propósito, queremos que DIOS configure correctamente el sistema en
        // vez de tener data fantasma en una sucursal que nadie eligió.
        //
        // Nota histórica: antes había un quinto paso que caía a la branch
        // default de la marca. Lo eliminamos cuando decidimos que el sistema
        // no impone una sucursal default — todo lo elige DIOS explícitamente.
        return Optional.empty();
    }

    /**
     * El claim `branchIds` viene como List<?>. JJWT lo serializa como
     * java.util.ArrayList con Integer/Long mezclado según deserialización.
     * Esto lo normaliza a List<Long>.
     */
    @SuppressWarnings("unchecked")
    private static List<Long> extractBranchIds(Object attr) {
        if (attr instanceof Collection<?> c) {
            return c.stream()
                    .map(BranchResolverFilter::extractLong)
                    .filter(x -> x != null)
                    .toList();
        }
        return List.of();
    }

    private static Long extractLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException nfe) { return null; }
    }
}
