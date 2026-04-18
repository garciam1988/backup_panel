package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.MemberPaymentPlan;
import app.coincidir.api.domain.payment.PaymentPlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberPaymentPlanRepository extends JpaRepository<MemberPaymentPlan, Long> {

    @Query("""
            select distinct p
              from MemberPaymentPlan p
              left join fetch p.installments i
             where p.planType = :type
            """)
    List<MemberPaymentPlan> findAllByPlanTypeWithInstallments(@Param("type") PaymentPlanType type);

    @Query("""
            select distinct p
              from MemberPaymentPlan p
              left join fetch p.installments i
             where p.id in :ids
            """)
    List<MemberPaymentPlan> findAllByIdInWithInstallments(@Param("ids") List<Long> ids);

    @Query("""
            select distinct p
              from MemberPaymentPlan p
              left join fetch p.installments i
             where p.groupId = :groupId
               and p.memberId = :memberId
            """)
    Optional<MemberPaymentPlan> findOneWithInstallments(
            @Param("groupId") Long groupId,
            @Param("memberId") Long memberId
    );

    Optional<MemberPaymentPlan> findByGroupIdAndMemberId(Long groupId, Long memberId);

    Optional<MemberPaymentPlan> findByGroupIdIsNullAndMemberId(Long memberId);

    @Query("""
            select distinct p
              from MemberPaymentPlan p
              left join fetch p.installments i
             where p.groupId is null
               and p.memberId = :memberId
            """)
    Optional<MemberPaymentPlan> findUngroupedWithInstallments(@Param("memberId") Long memberId);

    @Query("""
            select distinct p
              from MemberPaymentPlan p
              left join fetch p.installments i
             where p.groupId = :groupId
            """)
    List<MemberPaymentPlan> findAllByGroupIdWithInstallments(@Param("groupId") Long groupId);

    List<MemberPaymentPlan> findAllByMemberId(Long memberId);

    void deleteByMemberId(Long memberId);
}