package app.coincidir.api.domain.backups;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "backup_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupSettings {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "daily_time", nullable = false, length = 10)
    private String dailyTime;

    @Column(name = "last_daily_run")
    private LocalDate lastDailyRun;

    @PrePersist
    public void prePersist() {
        if (id == null) id = 1L;
        if (dailyTime == null || dailyTime.isBlank()) dailyTime = "02:00";
    }
}
