package app.coincidir.api.repository;

import app.coincidir.api.domain.Prestador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PrestadorRepository extends JpaRepository<Prestador, Long> {

    List<Prestador> findByActivoTrueOrderByNombreAsc();

    /**
     * Proyección liviana para combos (evita problemas de mapeo JPA por joins/fechas).
     */
    interface PrestadorIdNameProjection {
        Long getId();
        String getNombre();
    }

    /**
     * Devuelve prestadores asociados a una excursión (join por tabla prestadores_x_excursiones).
     * Nota: considera activo=1 o NULL como “activo” para compatibilidad.
     */
    @Query(value = """
            select p.id as id, p.nombre as nombre
              from prestadores p
              join prestadores_x_excursiones pxe
                on pxe.prestador_id = p.id
             where pxe.excursion_id = :excursionId
               and (p.activo = 1 or p.activo is null)
             order by p.nombre asc
            """, nativeQuery = true)
    List<PrestadorIdNameProjection> findIdNameByExcursionId(@Param("excursionId") Long excursionId);

    /**
     * MySQL devuelve 0/1 como número para expresiones booleanas. Para evitar ClassCastException
     * (Long -> Boolean), devolvemos count como Long y lo evaluamos en el service.
     */
    @Query(value = """
            select count(1)
              from prestadores_x_excursiones pxe
             where pxe.excursion_id = :excursionId
               and pxe.prestador_id = :prestadorId
            """, nativeQuery = true)
    Long countMappingNative(@Param("excursionId") Long excursionId, @Param("prestadorId") Long prestadorId);
}
