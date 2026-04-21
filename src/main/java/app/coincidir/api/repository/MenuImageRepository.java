package app.coincidir.api.repository;

import app.coincidir.api.domain.MenuImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MenuImageRepository extends JpaRepository<MenuImage, Long> {
    /** Orden estable para el admin: más recientes primero (no reordena al cambiar role). */
    List<MenuImage> findAllByOrderByIdDesc();

    /** Orden operativo (bot): por rol + sortOrder. Se usa cuando el bot busca por rol. */
    List<MenuImage> findAllByOrderByRoleAscSortOrderAscIdAsc();
    List<MenuImage> findByActiveTrueOrderByRoleAscSortOrderAscIdAsc();
}
