package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.TravelPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TravelPackageRepository extends JpaRepository<TravelPackage, Long> {
    List<TravelPackage> findByActiveTrueOrderByFechaSalidaAsc();
    List<TravelPackage> findAllByOrderByFechaSalidaAsc();
    Optional<TravelPackage> findByCodigo(String codigo);
}
