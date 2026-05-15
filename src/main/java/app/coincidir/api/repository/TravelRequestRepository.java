package app.coincidir.api.repository;

import app.coincidir.api.domain.RequestStatus;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TravelRequestRepository extends JpaRepository<TravelRequest, Long> {

    List<TravelRequest> findByGroup(TravelGroup group);  // o findByTravelGroup(...) según el nombre del campo

    List<TravelRequest> findByDestination(String destination);

    List<TravelRequest> findByStatusAndGroupIsNull(RequestStatus status);

    @Query("""
  SELECT tr FROM TravelRequest tr
  WHERE (:from IS NULL OR tr.createdAt >= :from)
  AND (:to IS NULL OR tr.createdAt <= :to)
  """)
    List<TravelRequest> findAllByDateRange(LocalDateTime from, LocalDateTime to);
    List<TravelRequest> findByGroupId(Long groupId);

    @Query("""
      SELECT tr FROM TravelRequest tr
      WHERE tr.status = 'NEW'
    """)
    List<TravelRequest> findAllNew();

    @Query("""
      SELECT r
        FROM TravelRequest r
       WHERE r.group IS NULL
         AND r.status IN (
             app.coincidir.api.domain.RequestStatus.NEW,
             app.coincidir.api.domain.RequestStatus.SEARCHING
         )
      """)

    List<TravelRequest> findAllEligible();

    /** Reclama las N requests que vas a procesar (pasa de NEW -> SEARCHING). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
      UPDATE TravelRequest tr
         SET tr.status = app.coincidir.api.domain.RequestStatus.SEARCHING
       WHERE tr.id IN :ids
         AND tr.status = app.coincidir.api.domain.RequestStatus.NEW
      """)
    int markAsSearching(@Param("ids") List<Long> ids);

    /** Adjunta el grupo y marca como GROUPED solo si siguen en SEARCHING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    UPDATE TravelRequest tr
       SET tr.group  = :group,
           tr.status = app.coincidir.api.domain.RequestStatus.GROUPED
     WHERE tr.id IN :ids
       AND tr.status = app.coincidir.api.domain.RequestStatus.SEARCHING
    """)
    int bulkAttachGroup(@Param("ids") List<Long> ids, @Param("group") TravelGroup group);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
  UPDATE TravelRequest tr
     SET tr.group = NULL,
         tr.status = app.coincidir.api.domain.RequestStatus.NEW
   WHERE tr.group.id = :groupId
""")
    int resetAllByGroup(@Param("groupId") Long groupId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
  UPDATE TravelRequest tr
     SET tr.group = NULL,
         tr.status = app.coincidir.api.domain.RequestStatus.NEW
   WHERE tr.id = :requestId AND tr.group.id = :groupId
""")
    int resetOneFromGroup(@Param("groupId") Long groupId, @Param("requestId") Long requestId);

    /**
     * Busca todas las solicitudes asociadas a un email.
     * Se usa para el panel de usuario (mi grupo).
     */
    List<TravelRequest> findByEmail(String email);

    /**
     * Igual que findByEmail pero sin sensibilidad a mayúsculas/minúsculas.
     */
    List<TravelRequest> findByEmailIgnoreCase(String email);

    /**
     * Un usuario puede tener multiples solicitudes; devolvemos la mas reciente.
     */
    Optional<TravelRequest> findTopByEmailOrderByIdDesc(String email);

    /**
     * Igual que findTopByEmailOrderByIdDesc pero sin sensibilidad a mayúsculas/minúsculas.
     */
    Optional<TravelRequest> findTopByEmailIgnoreCaseOrderByIdDesc(String email);

    /**
     * Para aplicar a un grupo desde el panel de usuario: última solicitud sin grupo.
     */
    Optional<TravelRequest> findTopByEmailAndGroupIsNullOrderByIdDesc(String email);

    /**
     * Igual que findTopByEmailAndGroupIsNullOrderByIdDesc pero sin sensibilidad a mayúsculas/minúsculas.
     */
    Optional<TravelRequest> findTopByEmailIgnoreCaseAndGroupIsNullOrderByIdDesc(String email);

    List<TravelRequest> findByGroupIdOrderByIdAsc(Long groupId);

    long countByGroupId(Long groupId);

    // ============================================================
    // Lookup de cliente (documento / email)
    // ============================================================

    java.util.Optional<app.coincidir.api.domain.TravelRequest> findTopByDniOrderByIdDesc(String dni);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndDni(String email, String dni);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);


}

