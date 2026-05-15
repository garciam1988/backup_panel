package app.coincidir.api.repository;

import app.coincidir.api.domain.TransferPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferPointRepository extends JpaRepository<TransferPoint, Long> {
    List<TransferPoint> findAllByActiveTrueOrderByNameAsc();
}
