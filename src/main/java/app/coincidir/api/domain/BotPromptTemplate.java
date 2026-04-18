package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotPromptTemplate — Plantilla de prompt por rubro.
 *
 * El admin elige una plantilla del dropdown en /admin y esa pasa a ser la que
 * el bot usa. Si el admin edita el texto y guarda, se actualiza esta misma
 * fila (no se crea una nueva).
 *
 * La FK está en bot_config.active_prompt_template_id apuntando a esta tabla.
 */
@Entity
@Table(name = "bot_prompt_template", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bot_prompt_template_name", columnNames = "name")
})
@Getter @Setter
public class BotPromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre del rubro. Ej: "Hoteles", "Gastronomía", "Agencia de viajes". */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Descripción corta opcional (ej: para mostrar debajo del nombre en el dropdown). */
    @Column(name = "description", length = 300)
    private String description;

    /** El prompt completo. */
    @Column(name = "prompt_text", nullable = false, columnDefinition = "LONGTEXT")
    private String promptText;

    /** Si está en false, no aparece en el dropdown del admin. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

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
