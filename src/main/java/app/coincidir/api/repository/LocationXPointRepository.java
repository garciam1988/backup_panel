package app.coincidir.api.repository;

import app.coincidir.api.domain.LocationXPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocationXPointRepository extends JpaRepository<LocationXPoint, Long> {
    List<LocationXPoint> findAllByTransferLocationIdOrderByTransferPointNameAsc(Long transferLocationId);
}
