// app/coincidir/api/repository/TravelGroupRepository.java
package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.TravelGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// repository/TravelGroupRepository.java
public interface TravelGroupRepository extends JpaRepository<TravelGroup, Long> {

    Page<TravelGroup> findAllByStatus(GroupStatus status, Pageable pageable);

    List<TravelGroup> findByStatusInAndTravelEndDateBefore(List<GroupStatus> statuses, java.time.LocalDate date);

    @Query("""
    select g from TravelGroup g
    left join fetch g.members m
    where g.id = :id
  """)
    Optional<TravelGroup> fetchDetail(@Param("id") Long id);

    // Trae grupos + miembros en una sola ida (para la grilla)
    @Query("""
      SELECT DISTINCT g
        FROM TravelGroup g
        LEFT JOIN FETCH g.members m
       WHERE (:status IS NULL OR g.status = :status)
       ORDER BY g.createdAt DESC
    """)
    List<TravelGroup> findAllWithMembers(@Param("status") GroupStatus status);

    /**
     * Sugeridos para el panel de usuario (compat con endpoints legacy).
     * Coincide por destino + whenLabel + companionPreference + ageBucket.
     *
     * Nota: el status se filtra por un conjunto (OPEN/NEGOTIATION/FORMED, etc.)
     * para evitar que un cambio de default deje el panel sin sugerencias.
     */
    @Query("""
      SELECT g
        FROM TravelGroup g
       WHERE LOWER(g.destination) = LOWER(:dest)
         AND LOWER(g.whenLabel) = LOWER(:when)
         AND (:pref IS NULL OR :pref = '' OR LOWER(g.companionPreference) = LOWER(:pref))
         AND (:ageBucket IS NULL OR :ageBucket = '' OR LOWER(g.ageBucket) = LOWER(:ageBucket))
         AND g.status IN :statuses
       ORDER BY g.createdAt DESC
    """)
    List<TravelGroup> findSuggested(
            @Param("dest") String dest,
            @Param("when") String when,
            @Param("pref") String pref,
            @Param("ageBucket") String ageBucket,
            @Param("statuses") List<GroupStatus> statuses
    );

    /**
     * Fallback: sugeridos solo por destino + whenLabel (+ statuses), sin pref/edad.
     */
    @Query("""
      SELECT g
        FROM TravelGroup g
       WHERE LOWER(g.destination) = LOWER(:dest)
         AND LOWER(g.whenLabel) = LOWER(:when)
         AND g.status IN :statuses
       ORDER BY g.createdAt DESC
    """)
    List<TravelGroup> findSuggestedLoose(
            @Param("dest") String dest,
            @Param("when") String when,
            @Param("statuses") List<GroupStatus> statuses
    );

    /**
     * Último fallback: solo por destino (+ statuses).
     */
    @Query("""
      SELECT g
        FROM TravelGroup g
       WHERE LOWER(g.destination) = LOWER(:dest)
         AND g.status IN :statuses
       ORDER BY g.createdAt DESC
    """)
    List<TravelGroup> findSuggestedByDestination(
            @Param("dest") String dest,
            @Param("statuses") List<GroupStatus> statuses
    );


    // Grupo temporal para pagos/conciliación antes de generar el grupo real
    Optional<TravelGroup> findFirstByDestinationIgnoreCaseAndWhenLabelIgnoreCase(String destination, String whenLabel);

    @Query("""
      SELECT g
        FROM TravelGroup g
       WHERE LOWER(g.destination) = LOWER(:dest)
       ORDER BY g.id ASC
    """)
    List<TravelGroup> findAllByDestinationOrdered(@Param("dest") String dest);

}

