package app.coincidir.api.repository;

import app.coincidir.api.domain.expense.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    Optional<Expense> findFirstByServicePaymentRecordId(Long servicePaymentRecordId);

    java.util.List<Expense> findAllByGroupIdAndNotesContaining(Long groupId, String notes);

    Optional<Expense> findFirstByGroupIdAndMenuItemIdAndNotesContaining(Long groupId, Long menuItemId, String notes);

    java.util.List<Expense> findAllByGroupIdAndMenuItemIdOrderByDateAscIdAsc(Long groupId, Long menuItemId);

    @Query("select e from Expense e where e.notes is not null and e.notes like concat('%', :token, '%')")
    java.util.List<Expense> findAllByNotesContaining(@Param("token") String token);

}

