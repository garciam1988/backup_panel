package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "ai_learned_rule")
@Getter @Setter
public class AiLearnedRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Título corto del finding que originó la regla */
    @Column(name = "finding_title", nullable = false, length = 300)
    private String findingTitle;

    /** Descripción del finding original (para contexto en el prompt) */
    @Column(name = "finding_description", columnDefinition = "TEXT")
    private String findingDescription;

    /** Razón opcional que ingresó el usuario */
    @Column(name = "user_reason", columnDefinition = "TEXT")
    private String userReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
