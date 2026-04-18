package app.coincidir.api.reports.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "report_definition",
        indexes = {
                @Index(name = "idx_report_def_panel", columnList = "panel"),
                @Index(name = "idx_report_def_category", columnList = "category"),
                @Index(name = "idx_report_def_ds", columnList = "data_source_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportDefinition {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32)
    private String panel;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 80)
    private String category;

    @Column(name = "data_source_id", nullable = false, length = 64)
    private String dataSourceId;

    @Lob
    @Column(name = "config_json", columnDefinition = "LONGTEXT")
    private String configJson;

    @Column(name = "is_template", nullable = false)
    private boolean template;

    @Column(name = "is_shared", nullable = false)
    private boolean shared;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (panel == null) panel = "ADMIN";
        if (name == null) name = "Reporte";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
