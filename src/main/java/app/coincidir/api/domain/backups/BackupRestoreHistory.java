package app.coincidir.api.domain.backups;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "backup_restore_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupRestoreHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "dump_file_name", length = 255)
    private String dumpFileName;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "performed_by", length = 255)
    private String performedBy;
}
