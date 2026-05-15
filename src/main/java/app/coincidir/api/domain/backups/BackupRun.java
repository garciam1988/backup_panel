package app.coincidir.api.domain.backups;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "backup_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "trigger_type", length = 30)
    private String trigger;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "message", length = 2000)
    private String message;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
