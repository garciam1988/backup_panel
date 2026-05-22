package app.coincidir.api.tenancy.repository;

import app.coincidir.api.tenancy.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    /** Sucursal por (brandId, slug) — usado para resolver la URL del bot público. */
    Optional<Branch> findByBrandIdAndSlug(Long brandId, String slug);

    /** Default de la marca — fallback cuando no se especifica branch. */
    Optional<Branch> findByBrandIdAndDefaultForBrandTrue(Long brandId);

    /** Todas las sucursales activas de una marca. */
    List<Branch> findByBrandIdAndActiveTrueOrderByNameAsc(Long brandId);

    /** Todas las sucursales de una marca (activas e inactivas). Lo usa la pantalla
     *  de gestión donde el admin necesita ver/reactivar también las inactivas. */
    List<Branch> findByBrandIdOrderByNameAsc(Long brandId);

    /** Cuántas sucursales activas tiene una marca. Útil para validar que no
     *  se borre/desactive la última. */
    long countByBrandIdAndActiveTrue(Long brandId);
}
