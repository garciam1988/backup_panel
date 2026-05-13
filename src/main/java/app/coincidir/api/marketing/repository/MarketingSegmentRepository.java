package app.coincidir.api.marketing.repository;

import app.coincidir.api.marketing.domain.MarketingSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketingSegmentRepository extends JpaRepository<MarketingSegment, Long> {

    List<MarketingSegment> findByDeletedAtIsNullAndActiveTrueOrderByNameAsc();

    List<MarketingSegment> findByDeletedAtIsNullOrderByNameAsc();
}
