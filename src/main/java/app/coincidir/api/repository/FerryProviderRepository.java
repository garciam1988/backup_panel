package app.coincidir.api.repository;

import app.coincidir.api.domain.FerryProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FerryProviderRepository extends JpaRepository<FerryProvider, Long> {

    List<FerryProvider> findAllByActivoTrueOrderByNombreAsc();

    Optional<FerryProvider> findByNombreIgnoreCase(String nombre);
}
