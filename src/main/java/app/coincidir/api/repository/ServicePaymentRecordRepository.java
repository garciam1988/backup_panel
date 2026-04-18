package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.ServicePaymentRecord;
import app.coincidir.api.domain.operations.OperationStatusCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ServicePaymentRecordRepository extends JpaRepository<ServicePaymentRecord, Long> {
    List<ServicePaymentRecord> findByPlanIdOrderByPaymentDateAscIdAsc(Long planId);

    /**
     * ALOJAMIENTOS: Vencimientos por fecha de cancelación total (cuando el plan es de tipo SEÑA).
     * Se usa para listar próximos vencimientos en estado SEÑADO.
     */
    @Query("""
            select r
            from ServicePaymentRecord r
            join r.plan p
            join p.menuItem mi
            join mi.service s
            where p.paymentForm = app.coincidir.api.domain.payment.ServicePaymentForm.SENA
              and mi.operationStatus = :status
              and s.code = app.coincidir.api.domain.ServiceCode.ALOJAMIENTOS
              and r.totalPaymentCancellationDate is not null
              and r.totalPaymentCancellationDate between :start and :end
            """)
    List<ServicePaymentRecord> findExpiringTotalCancellationCandidates(
            @Param("status") OperationStatusCode status,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );
}
