package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberOptionalLuggageService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;

public interface MemberOptionalLuggageServiceRepository extends JpaRepository<MemberOptionalLuggageService, Long> {

    Optional<MemberOptionalLuggageService> findByMenuItemIdAndMemberId(Long menuItemId, Long memberId);

    void deleteByMenuItemId(Long menuItemId);

    List<MemberOptionalLuggageService> findByMemberId(Long memberId);

    @Query("select coalesce(sum(s.sale), 0) from MemberOptionalLuggageService s where s.member.group.id = :groupId")
    BigDecimal sumSaleByGroupId(@Param("groupId") Long groupId);

    @Query("select count(s.id) from MemberOptionalLuggageService s where s.member.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);

    void deleteByMemberId(Long memberId);
}