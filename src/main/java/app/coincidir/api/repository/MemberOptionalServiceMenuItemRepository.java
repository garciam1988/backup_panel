package app.coincidir.api.repository;

import app.coincidir.api.domain.MemberOptionalServiceCode;
import app.coincidir.api.domain.MemberOptionalServiceMenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberOptionalServiceMenuItemRepository extends JpaRepository<MemberOptionalServiceMenuItem, Long> {

    List<MemberOptionalServiceMenuItem> findByMemberIdOrderByPositionAsc(Long memberId);

    Optional<MemberOptionalServiceMenuItem> findByIdAndMemberId(Long id, Long memberId);

    long countByMemberIdAndServiceCode(Long memberId, MemberOptionalServiceCode serviceCode);

    @Query("select coalesce(max(i.position), 0) from MemberOptionalServiceMenuItem i where i.member.id = :memberId")
    Integer findMaxPositionByMemberId(@Param("memberId") Long memberId);

    /**
     * Fast check used by Operations table to know if a group has any optional services.
     */
    @Query("select count(i.id) from MemberOptionalServiceMenuItem i where i.member.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);

    void deleteByMemberId(Long memberId);
}