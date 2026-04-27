package app.coincidir.api.apiusage.repository;

import app.coincidir.api.apiusage.domain.ApiPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiPricingRepository extends JpaRepository<ApiPricing, Long> {

    Optional<ApiPricing> findByProviderAndModel(String provider, String model);

    /** Para fallback: precio "default" del provider cuando el modelo específico
     *  no está cargado en la tabla. */
    Optional<ApiPricing> findByProviderAndModelIsNull(String provider);

    List<ApiPricing> findByActiveTrueOrderByProviderAscModelAsc();

    List<ApiPricing> findAllByOrderByProviderAscModelAsc();
}
