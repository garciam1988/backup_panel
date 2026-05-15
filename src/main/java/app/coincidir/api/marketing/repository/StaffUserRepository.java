package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    /** Para listar en /marketing → Staff (ordenados por nombre asc). */
    List<StaffUser> findAllByOrderByNameAsc();

    /**
     * Para login: el caller hashea el PIN ingresado y busca match.
     * Filtra automáticamente por active=true porque si está desactivado,
     * el mozo no puede loguearse.
     */
    Optional<StaffUser> findByPinHashAndActiveTrue(String pinHash);
}
