// src/main/java/app/coincidir/api/repository/MemberTransferServiceRepository.java
package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberTransferService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface MemberTransferServiceRepository extends JpaRepository<MemberTransferService, Long> {

    Optional<MemberTransferService> findByMenuItemIdAndMemberId(Long menuItemId, Long memberId);

    List<MemberTransferService> findByMenuItemId(Long menuItemId);

    List<MemberTransferService> findByMemberId(Long memberId);
    void deleteByMenuItemId(Long menuItemId);


@Query("select coalesce(sum(m.quotedValue), 0) from MemberTransferService m where m.member.group.id = :groupId")
BigDecimal sumQuotedValueByGroupId(@Param("groupId") Long groupId);




@Query("select coalesce(sum(m.quotedValue), 0) from MemberTransferService m where m.member.id = :memberId")
BigDecimal sumQuotedValueByMemberId(@Param("memberId") Long memberId);
    void deleteByMemberId(Long memberId);
}