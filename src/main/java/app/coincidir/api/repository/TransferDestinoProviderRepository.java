package app.coincidir.api.repository;

import app.coincidir.api.domain.TransferDestinoProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferDestinoProviderRepository extends JpaRepository<TransferDestinoProvider, Long> {

    List<TransferDestinoProvider> findAllByActivoTrueOrderByNombreAsc();
}
