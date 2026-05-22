package app.coincidir.api.tenancy.service;

import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.Brand;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * BranchResolverService — resuelve la sucursal activa a partir de la
 * info disponible en el request (header, path, JWT, etc).
 *
 * Centralizamos la lógica acá para que el filtro HTTP, el bot público,
 * los jobs y los tests usen la misma cadena de resolución.
 *
 * Estrategia de resolución (en orden, gana el primer match):
 *
 *   1. Header explícito `X-Branch-Id` (numérico) — usado por el admin
 *      cuando el usuario eligió una sucursal del selector.
 *
 *   2. Header `X-Branch-Slug` + Header `X-Brand-Slug` — útil para
 *      requests programáticos donde el caller conoce los slugs pero no
 *      los IDs.
 *
 *   3. Path del bot público (`/palermo`) — el frontend del bot lee el
 *      primer segmento de location.pathname y lo manda como
 *      `X-Branch-Slug` sin `X-Branch-Id`. La marca se infiere por host
 *      o por config global.
 *
 *   4. Fallback a la sucursal default de la marca — si nada de lo
 *      anterior matcheó, se usa la branch con default_for_brand=true.
 *
 * Si NADA se puede resolver (BD vacía, marca no encontrada), devuelve
 * Optional.empty() y el filtro responde 404 — eso indica un sistema
 * mal configurado, no debería pasar en runtime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchResolverService {

    private final BrandRepository brandRepo;
    private final BranchRepository branchRepo;

    /**
     * Resuelve por branch_id directo. Es el caso más simple — se usa
     * cuando el frontend del admin manda el id que tenía en localStorage.
     */
    @Transactional(readOnly = true)
    public Optional<BranchScope> resolveByBranchId(Long branchId) {
        if (branchId == null) return Optional.empty();
        return branchRepo.findById(branchId)
                .filter(Branch::getActive)
                .flatMap(this::toScope);
    }

    /**
     * Resuelve por (brand_slug, branch_slug). Útil cuando hay multi-marca
     * en el mismo deploy. Si brand_slug es null, asume la marca única
     * existente (caso típico hoy: 1 deploy = 1 marca).
     */
    @Transactional(readOnly = true)
    public Optional<BranchScope> resolveBySlugs(String brandSlug, String branchSlug) {
        Brand brand = resolveBrand(brandSlug).orElse(null);
        if (brand == null) return Optional.empty();

        if (branchSlug == null || branchSlug.isBlank()) {
            // Sin slug de branch → usar default de la marca
            return branchRepo.findByBrandIdAndDefaultForBrandTrue(brand.getId())
                    .flatMap(b -> toScope(brand, b));
        }
        return branchRepo.findByBrandIdAndSlug(brand.getId(), branchSlug.trim().toLowerCase())
                .filter(Branch::getActive)
                .flatMap(b -> toScope(brand, b));
    }

    /**
     * Fallback total: devuelve la sucursal default de la única marca
     * existente.
     *
     * @deprecated YA NO SE USA. Antes el BranchResolverFilter caía acá cuando
     * ningún otro método resolvía el branch, pero eso causaba que data se
     * guardara silenciosamente en "Casa Central" sin que el admin la hubiera
     * elegido. Lo eliminamos en mayo 2026 (decisión de producto: el sistema
     * no impone una branch default — la elige DIOS desde la UI o no hay
     * scope, punto).
     *
     * Lo dejamos como dead code por si en el futuro hace falta para algún
     * job interno que necesite una branch específica.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Optional<BranchScope> resolveDefault() {
        return resolveBrand(null)
                .flatMap(brand -> branchRepo.findByBrandIdAndDefaultForBrandTrue(brand.getId())
                        .flatMap(b -> toScope(brand, b)));
    }

    /**
     * Resuelve la marca por slug. Si slug es null:
     *   - 1 marca en BD → devuelve esa (caso normal hoy).
     *   - >1 marca y sin slug → devuelve la primera con log warning.
     *   - 0 marcas → empty (sistema mal seedeado).
     */
    private Optional<Brand> resolveBrand(String brandSlug) {
        if (brandSlug != null && !brandSlug.isBlank()) {
            return brandRepo.findBySlug(brandSlug.trim().toLowerCase());
        }
        var all = brandRepo.findAll();
        if (all.isEmpty()) {
            log.error("[BranchResolver] No hay ninguna marca en BD — el seed del Bloque 1 no corrió");
            return Optional.empty();
        }
        if (all.size() > 1) {
            log.warn("[BranchResolver] Hay {} marcas pero el caller no especificó brand_slug — uso la primera ({})",
                    all.size(), all.get(0).getSlug());
        }
        return Optional.of(all.get(0));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Optional<BranchScope> toScope(Branch branch) {
        return brandRepo.findById(branch.getBrandId())
                .map(brand -> toScopeInternal(brand, branch));
    }

    private Optional<BranchScope> toScope(Brand brand, Branch branch) {
        return Optional.of(toScopeInternal(brand, branch));
    }

    private BranchScope toScopeInternal(Brand brand, Branch branch) {
        return BranchScope.of(brand.getId(), branch.getId(), brand.getSlug(), branch.getSlug());
    }
}
