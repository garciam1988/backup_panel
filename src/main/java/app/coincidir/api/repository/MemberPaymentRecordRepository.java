package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.MemberPaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MemberPaymentRecordRepository extends JpaRepository<MemberPaymentRecord, Long> {

    // listado general para conciliación (ordenado)
    List<MemberPaymentRecord> findAllByOrderByPaymentDateDescIdDesc();

    List<MemberPaymentRecord> findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(Long groupId, Long memberId);

    boolean existsByGroupIdAndMemberId(Long groupId, Long memberId);

    List<MemberPaymentRecord> findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(Long memberId);

    List<MemberPaymentRecord> findAllByGroupIdOrderByPaymentDateDescIdDesc(Long groupId);

    boolean existsByPlanId(Long planId);

    boolean existsByPlanIdAndInstallmentNumber(Long planId, Integer installmentNumber);

    @Query("""
            select r
              from MemberPaymentRecord r
             where r.plan.id = :planId
             order by r.paymentDate desc, r.id desc
            """)
    List<MemberPaymentRecord> findAllByPlanIdOrderByPaymentDateDescIdDesc(@Param("planId") Long planId);

    List<MemberPaymentRecord> findAllByPlanIdIn(Collection<Long> planIds);

    // Utilizado para enriquecer gastos/egresos con info del comprobante y grupo
    List<MemberPaymentRecord> findAllByReceiptLast4OrderByPaymentDateDescIdDesc(String receiptLast4);

    List<MemberPaymentRecord> findAllByReceiptLast4InOrderByPaymentDateDescIdDesc(Collection<String> receiptLast4s);

    @Query("""
            select (count(r) > 0)
              from MemberPaymentRecord r
             where r.receiptLast4 = :last4
               and r.receiptBlob is not null
            """)
    boolean existsWithReceiptByLast4(@Param("last4") String last4);
}
