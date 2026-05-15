package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberEmision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberEmisionRepository extends JpaRepository<MemberEmision, Long> {

    List<MemberEmision> findByEmittedFalseOrEmittedIsNullOrderByCreatedAtDesc();

    List<MemberEmision> findAllByOrderByCreatedAtDesc();

    List<MemberEmision> findByEmittedIsTrueAndTravelMonthIgnoreCase(String travelMonth);

    List<MemberEmision> findByEmittedIsTrueAndTravelMonthIgnoreCaseAndDestinationIgnoreCase(String travelMonth, String destination);

    List<MemberEmision> findByRequestIdInAndEmittedIsTrue(List<Long> requestIds);

    Optional<MemberEmision> findTopByRequestIdOrderByCreatedAtDesc(Long requestId);

    // Para combos/listados: todas las emisiones emitidas que tengan request_id
    List<MemberEmision> findByEmittedIsTrueAndRequestIdIsNotNull();
}
