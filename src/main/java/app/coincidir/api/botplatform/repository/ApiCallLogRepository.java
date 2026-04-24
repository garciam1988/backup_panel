package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ApiCallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {

    Page<ApiCallLog> findAllByOrderByCalledAtDesc(Pageable pageable);

    Page<ApiCallLog> findByIntegrationIdOrderByCalledAtDesc(Long integrationId, Pageable pageable);

    Page<ApiCallLog> findByOkOrderByCalledAtDesc(Boolean ok, Pageable pageable);

    Page<ApiCallLog> findByIntegrationIdAndOkOrderByCalledAtDesc(Long integrationId, Boolean ok, Pageable pageable);

    /** Cleanup: borra logs anteriores a la fecha indicada. */
    @Modifying
    @Query("DELETE FROM ApiCallLog l WHERE l.calledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
