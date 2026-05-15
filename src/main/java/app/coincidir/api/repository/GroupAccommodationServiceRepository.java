package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupAccommodationService;
import app.coincidir.api.domain.operations.OperationStatusCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

import java.util.Optional;

public interface GroupAccommodationServiceRepository
        extends JpaRepository<GroupAccommodationService, Long> {

    Optional<GroupAccommodationService> findByMenuItemId(Long menuItemId);
    void deleteByMenuItemId(Long menuItemId);

    @Query("""
            select a from GroupAccommodationService a
            join fetch a.menuItem mi
            join fetch mi.group g
            join fetch mi.service s
            where mi.operationStatus = :status
              and a.reservationDueDate is not null
              and a.reservationDueDate between :startDate and :endDate
            """)
    List<GroupAccommodationService> findExpiringReservationCandidates(
            @Param("status") OperationStatusCode status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );


    @Query("""
            select a from GroupAccommodationService a
            join fetch a.menuItem mi
            join fetch mi.group g
            join fetch mi.service s
            where mi.operationStatus in :statuses
              and a.reservationDueDate is not null
              and a.reservationDueDate between :startDate and :endDate
            """)
    List<GroupAccommodationService> findExpiringReservationCandidatesIn(
            @Param("statuses") java.util.Set<OperationStatusCode> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );


}
