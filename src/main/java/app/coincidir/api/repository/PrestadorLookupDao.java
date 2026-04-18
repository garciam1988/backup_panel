package app.coincidir.api.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lookup de prestadores por excursión.
 *
 * Usa el esquema confirmado por DB:
 * - prestadores(id, nombre, activo, ...)
 * - prestadores_x_excursiones(excursion_id, prestador_id)
 *
 * Nota: Para evitar combos vacíos cuando el nombre está null/empty, se devuelve fallback "Prestador <id>".
 */
@Repository
@RequiredArgsConstructor
public class PrestadorLookupDao {

    private final JdbcTemplate jdbc;

    public record IdName(Long id, String nombre) {}

    public List<IdName> findIdNameByExcursionId(Long excursionId) {
        // No filtramos por activo: si está relacionado, se ofrece en el combo.
        final String sql = """
                select p.id as id,
                       coalesce(nullif(trim(p.nombre), ''), concat('Prestador ', p.id)) as nombre
                  from prestadores p
                  join prestadores_x_excursiones pxe
                    on pxe.prestador_id = p.id
                 where pxe.excursion_id = ?
                 order by nombre asc
                """;

        try {
            return jdbc.query(
                    sql,
                    (rs, rowNum) -> new IdName(rs.getLong("id"), rs.getString("nombre")),
                    excursionId
            );
        } catch (DataAccessException e) {
            // Si hubiera un esquema distinto en algún entorno, fallamos explícitamente para que sea visible en logs.
            throw e;
        }
    }

    public boolean existsMapping(Long excursionId, Long prestadorId) {
        final String sql = """
                select count(1)
                  from prestadores_x_excursiones pxe
                 where pxe.excursion_id = ?
                   and pxe.prestador_id = ?
                """;

        Long c = jdbc.queryForObject(sql, Long.class, excursionId, prestadorId);
        return c != null && c > 0;
    }
}
