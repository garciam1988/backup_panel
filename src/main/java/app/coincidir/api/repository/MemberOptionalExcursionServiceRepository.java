package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberOptionalExcursionService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;

public interface MemberOptionalExcursionServiceRepository extends JpaRepository<MemberOptionalExcursionService, Long> {

    Optional<MemberOptionalExcursionService> findByMenuItemIdAndMemberId(Long menuItemId, Long memberId);

    void deleteByMenuItemId(Long menuItemId);

    List<MemberOptionalExcursionService> findByMemberId(Long memberId);

    @Query("select coalesce(sum(s.sale), 0) from MemberOptionalExcursionService s where s.member.group.id = :groupId")
    BigDecimal sumSaleByGroupId(@Param("groupId") Long groupId);

    @Query("select count(s.id) from MemberOptionalExcursionService s where s.member.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);

    void deleteByMemberId(Long memberId);
}