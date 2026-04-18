package app.coincidir.api.repository.backups;

import app.coincidir.api.domain.backups.BackupRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {
    Optional<BackupRun> findTopByFileNameOrderByCreatedAtDesc(String fileName);
}
