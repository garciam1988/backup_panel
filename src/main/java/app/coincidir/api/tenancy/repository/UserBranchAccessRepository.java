package app.coincidir.api.tenancy.repository;

import app.coincidir.api.tenancy.domain.UserBranchAccess;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** Borra todos los accesos de un user (al eliminar el user). */
    void deleteByUserId(Long userId);
}
