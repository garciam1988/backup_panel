package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupServiceMenuItem;
import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.operations.OperationStatusCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface GroupServiceMenuItemRepository extends JpaRepository<GroupServiceMenuItem, Long> {

    List<GroupServiceMenuItem> findByGroupIdOrderByPositionAsc(Long groupId);

    @Query("select coalesce(sum(i.quotedValue), 0) from GroupServiceMenuItem i where i.group.id = :groupId and i.quotedValue is not null and i.quotedValue > 0")
    BigDecimal sumQuotedValueByGroupId(@Param("groupId") Long groupId);

    Optional<GroupServiceMenuItem> findByIdAndGroupId(Long id, Long groupId);

    Optional<GroupServiceMenuItem> findFirstByGroupIdAndService_CodeOrderByPositionAsc(Long groupId, ServiceCode code);

    List<GroupServiceMenuItem> findAllByService_CodeAndOperationStatus(ServiceCode code, OperationStatusCode operationStatus);

    @Query("select coalesce(max(i.position), 0) from GroupServiceMenuItem i where i.group.id = :groupId")
    int findMaxPosition(@Param("groupId") Long groupId);

    @Query("select count(i) from GroupServiceMenuItem i where i.group.id = :groupId and i.service.code = :code")
    long countByGroupIdAndServiceCode(@Param("groupId") Long groupId, @Param("code") ServiceCode code);
}
