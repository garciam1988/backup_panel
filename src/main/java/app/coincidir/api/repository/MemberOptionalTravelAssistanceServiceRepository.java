package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberOptionalTravelAssistanceService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;

public interface MemberOptionalTravelAssistanceServiceRepository extends JpaRepository<MemberOptionalTravelAssistanceService, Long> {

    Optional<MemberOptionalTravelAssistanceService> findByMenuItemIdAndMemberId(Long menuItemId, Long memberId);

    void deleteByMenuItemId(Long menuItemId);

    List<MemberOptionalTravelAssistanceService> findByMemberId(Long memberId);

    @Query("select coalesce(sum(s.sale), 0) from MemberOptionalTravelAssistanceService s where s.member.group.id = :groupId")
    BigDecimal sumSaleByGroupId(@Param("groupId") Long groupId);

    @Query("select count(s.id) from MemberOptionalTravelAssistanceService s where s.member.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);

    void deleteByMemberId(Long memberId);
}