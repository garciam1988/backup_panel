package app.coincidir.api.repository;

import app.coincidir.api.domain.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppRoleRepository extends JpaRepository<AppRole, Long> {
    Optional<AppRole> findByCode(String code);
    Optional<AppRole> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
    List<AppRole> findAllByOrderByCodeAsc();
}
