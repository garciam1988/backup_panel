package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberOptionalServiceCode;
import app.coincidir.api.domain.payment.OptionalServicePaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OptionalServicePaymentRecordRepository extends JpaRepository<OptionalServicePaymentRecord, Long> {
    List<OptionalServicePaymentRecord> findByPlanIdOrderByPaymentDateAscIdAsc(Long planId);

    interface OptionalServicePaymentKey {
        Long getGroupId();
        Long getMemberId();
        LocalDate getPaymentDate();
        BigDecimal getAmount();
    }

    @Query("""
            select
                g.id as groupId,
                m.id as memberId,
                r.paymentDate as paymentDate,
                r.amount as amount
            from OptionalServicePaymentRecord r
            join r.plan p
            join p.menuItem mi
            join mi.member m
            join m.group g
            where mi.serviceCode = :serviceCode
              and m.id in :memberIds
              and g.id in :groupIds
            """)
    List<OptionalServicePaymentKey> findKeysByServiceCodeAndMemberIdsAndGroupIds(
            @Param("serviceCode") MemberOptionalServiceCode serviceCode,
            @Param("memberIds") List<Long> memberIds,
            @Param("groupIds") List<Long> groupIds
    );
}
