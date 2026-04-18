package app.coincidir.api.repository.backups;

import app.coincidir.api.domain.backups.BackupRestoreHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BackupRestoreHistoryRepository extends JpaRepository<BackupRestoreHistory, Long> {

    @Query("select h from BackupRestoreHistory h order by h.startedAt desc")
    List<BackupRestoreHistory> findLatest(Pageable pageable);
}
