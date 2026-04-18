package app.coincidir.api.repository;

import app.coincidir.api.domain.TravelDestination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TravelDestinationRepository extends JpaRepository<TravelDestination, Long> {

    List<TravelDestination> findByActiveTrueOrderBySortOrderAscNameAsc();
}
