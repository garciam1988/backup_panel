package app.coincidir.api.repository.backups;

import app.coincidir.api.domain.backups.BackupSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupSettingsRepository extends JpaRepository<BackupSettings, Long> {
}
