package app.coincidir.api.tenancy.repository;

import app.coincidir.api.tenancy.domain.UserBranchAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserBranchAccessRepository extends JpaRepository<UserBranchAccess, Long> {

    /** Todas las branches a las que un user tiene acceso. */
    List<UserBranchAccess> findByUserId(Long userId);

    /** Branches a las que un user tiene acceso, dentro de una marca. */
    List<UserBranchAccess> findByUserIdAndBranchIdIn(Long userId, List<Long> branchIds);

    /** Verifica si un user tiene acceso a una branch específica. */
    Optional<UserBranchAccess> findByUserIdAndBranchId(Long userId, Long branchId);

    /** Branch preferida del user (si la tiene). */
    Optional<UserBranchAccess> findByUserIdAndIsPreferredTrue(Long userId);

    /** Todos los users con acceso a una branch (para listados en /admin). */
    List<UserBranchAccess> findByBranchId(Long branchId);

    /**
     * Cuenta usuarios "vivos" asignados a una branch — es decir, accesos
     * cuyo `user_id` existe efectivamente en `panel_user`.
     *
     * Existe porque la tabla `user_branch_access` no tiene FK real contra
     * `panel_user` (es un modelo plano por diseño), así que pueden quedar
     * filas huérfanas cuando se borra un usuario sin limpiar sus accesos.
     * El controller de delete de usuarios YA limpia las filas asociadas,
     * pero esta query es la red de seguridad para datos legacy: el flujo
     * de "borrar sucursal" usaba `findByBranchId(...).size()` y contaba
     * huérfanas como si fueran usuarios reales, bloqueando el borrado
     * con un cartel "tiene 1 usuario(s) asignado(s)" cuando en la UI no
     * aparecía ninguno.
     *
     * Si esta query devuelve 0, la branch se puede borrar con seguridad
     * — no hay usuario real apuntando a ella.
     */
    @Query("SELECT COUNT(uba) FROM UserBranchAccess uba " +
           "WHERE uba.branchId = :branchId " +
           "AND EXISTS (SELECT 1 FROM PanelUser pu WHERE pu.id = uba.userId)")
    long countLiveUsersByBranchId(@Param("branchId") Long branchId);

    /**
     * Borra todas las filas de `user_branch_access` cuyo `user_id` no tiene
     * correspondencia en `panel_user`. Se invoca al borrar una sucursal o
     * como mantenimiento manual — limpia los huérfanos que pudieran haber
     * quedado de borrados de usuarios anteriores al fix del controller.
     *
     * Devuelve la cantidad de filas borradas (útil para logging).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM UserBranchAccess uba WHERE NOT EXISTS " +
           "(SELECT 1 FROM PanelUser pu WHERE pu.id = uba.userId)")
    int deleteOrphans();

    /** Borra todos los accesos de un user (al eliminar el user). */
    void deleteByUserId(Long userId);
}
