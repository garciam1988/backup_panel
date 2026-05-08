package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * UiText — string maestra (en español) que aparece en la UI del bot.
 *
 * Ejemplo:
 *   key = "btn_send"
 *   defaultText = "Enviar"
 *   category = "buttons"
 *   description = "Botón principal para mandar un mensaje en el chat"
 *
 * Las traducciones a otros idiomas se guardan en {@link UiTranslation}.
 *
 * El admin puede:
 *  - Editar el {@code defaultText} (texto en español)
 *  - Marcar la string como modificada (para invalidar traducciones cacheadas)
 *  - Las strings con {@code isSystem=true} fueron seedeadas por el código y
 *    no se pueden borrar, pero su texto SÍ se puede editar.
 */
@Entity
@Table(name = "app_ui_text", indexes = {
        @Index(name = "idx_app_ui_text_key", columnList = "ui_key", unique = true),
        @Index(name = "idx_app_ui_text_cat", columnList = "category")
})
@Getter @Setter
public class UiText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Clave única usada en el código (ej: "btn_send", "input_placeholder"). */
    @Column(name = "ui_key", length = 120, nullable = false, unique = true)
    private String key;

    /** Texto en español (idioma fuente). */
    @Column(name = "default_text", columnDefinition = "TEXT", nullable = false)
    private String defaultText;

    /** Categoría libre para agrupar en /admin (ej: "buttons", "menu", "errors"). */
    @Column(name = "category", length = 60)
    private String category;

    /**
     * Descripción opcional para que la IA tenga contexto al traducir.
     * Ej: "Botón en el chat para enviar mensaje. Debe ser corto, máximo 1-2 palabras."
     */
    @Column(name = "description", length = 500)
    private String description;

    /** Si es true, fue creado por el código y no se puede borrar (sólo editar). */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (isSystem == null) isSystem = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
