package app.coincidir.api.repository;

import app.coincidir.api.domain.TravelRequestAirService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TravelRequestAirServiceRepository extends JpaRepository<TravelRequestAirService, Long> {
    Optional<TravelRequestAirService> findByRequest_Id(Long requestId);

    boolean existsByRequest_IdIn(Collection<Long> requestIds);

    List<TravelRequestAirService> findAllByRequest_IdIn(Collection<Long> requestIds);
}
