package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ai_operation_suggestion", indexes = {
        @Index(name = "idx_ai_op_sugg_group", columnList = "group_id"),
        @Index(name = "idx_ai_op_sugg_dismissed", columnList = "dismissed")
})
@Getter
@Setter
public class AiOperationSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "group_destination", length = 255)
    private String groupDestination;

    @Column(name = "travel_start_date")
    private LocalDate travelStartDate;

    @Column(name = "travel_end_date")
    private LocalDate travelEndDate;

    /** Resumen breve (1 línea) — se muestra en la tabla */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** JSON array de findings detallados */
    @Column(name = "findings_json", columnDefinition = "LONGTEXT")
    private String findingsJson;

    /** ERROR / WARNING / OK */
    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed = false;

    /** JSON array de títulos de findings suprimidos para esta OP específica */
    @Column(name = "suppressed_findings_json", columnDefinition = "TEXT")
    private String suppressedFindingsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
