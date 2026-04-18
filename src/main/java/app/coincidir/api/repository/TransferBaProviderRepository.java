package app.coincidir.api.repository;

import app.coincidir.api.domain.TransferBaProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferBaProviderRepository extends JpaRepository<TransferBaProvider, Long> {

    List<TransferBaProvider> findAllByActivoTrueOrderByNombreAsc();
}
