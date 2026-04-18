// src/main/java/app/coincidir/api/repository/MemberAirServiceRepository.java
package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberAirService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface MemberAirServiceRepository extends JpaRepository<MemberAirService, Long> {

    Optional<MemberAirService> findByMenuItemIdAndMemberId(Long menuItemId, Long memberId);

    List<MemberAirService> findByMenuItemId(Long menuItemId);

    List<MemberAirService> findByMemberId(Long memberId);

    // Batch lookup (para armar combos/estadísticas sin N queries)
    List<MemberAirService> findByMemberIdIn(List<Long> memberIds);
    void deleteByMenuItemId(Long menuItemId);


@Query("select coalesce(sum(m.quotedValue), 0) from MemberAirService m where m.member.group.id = :groupId")
BigDecimal sumQuotedValueByGroupId(@Param("groupId") Long groupId);




@Query("select coalesce(sum(m.quotedValue), 0) from MemberAirService m where m.member.id = :memberId")
BigDecimal sumQuotedValueByMemberId(@Param("memberId") Long memberId);
    void deleteByMemberId(Long memberId);
}