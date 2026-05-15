package app.coincidir.api.repository;

import app.coincidir.api.domain.ExcursionCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExcursionCatalogRepository extends JpaRepository<ExcursionCatalog, Long> {


    /**
     * Catálogo por destino usando tablas normalizadas:
     * - destinos(id, descripcion, activo)
     * - excursiones_x_destinos(id_excursion, id_destino)
     */
    @Query(value = """
            select e.*
              from excursiones e
              join excursiones_x_destinos exd on exd.id_excursion = e.id
              join destinos d on d.id = exd.id_destino
             where e.activo = 1
               and d.activo = 1
               and lower(replace(replace(replace(replace(replace(trim(d.descripcion),' ',''),'-',''),'–',''),'_',''),'/','')) = lower(replace(replace(replace(replace(replace(trim(:destino),' ',''),'-',''),'–',''),'_',''),'/',''))
             order by coalesce(e.nombre, e.descripcion) asc
            """, nativeQuery = true)
    List<ExcursionCatalog> findActiveByDestinoDescripcion(@Param("destino") String destino);

    /**
     * MySQL devuelve 0/1 numérico para booleanos en queries nativas; Spring puede castear el resultado como Long.
     * Para evitar ClassCastException, devolvemos el conteo y evaluamos en el controller.
     */
    @Query(value = """
            select count(1)
              from excursiones_x_destinos exd
              join destinos d on d.id = exd.id_destino
             where exd.id_excursion = :excursionId
               and lower(replace(replace(replace(replace(replace(trim(d.descripcion),' ',''),'-',''),'–',''),'_',''),'/','')) = lower(replace(replace(replace(replace(replace(trim(:destino),' ',''),'-',''),'–',''),'_',''),'/',''))
            """, nativeQuery = true)
    Long countExcursionInDestino(@Param("excursionId") Long excursionId, @Param("destino") String destino);
}
