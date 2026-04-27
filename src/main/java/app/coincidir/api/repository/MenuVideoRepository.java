package app.coincidir.api.repository;

import app.coincidir.api.domain.MenuVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuVideoRepository extends JpaRepository<MenuVideo, Long> {
    List<MenuVideo> findByActiveTrueOrderByCreatedAtDesc();
    Optional<MenuVideo> findFirstByActiveTrueOrderByCreatedAtDesc();
}
