package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * UiTranslation — traducción de un {@link UiText} a un idioma específico.
 *
 * El par único es (uiTextId, languageCode). Si la entrada no existe para un
 * (key, lang) pedido, el servicio i18n la genera con IA al vuelo y la cachea
 * acá.
 *
 * - {@code source} indica si fue traducida por IA o editada manualmente.
 *   Si el admin edita una traducción (cambio manual), {@code source = MANUAL}
 *   y al regenerar traducciones con IA NO se sobrescribe (a menos que se
 *   pida explícitamente "regenerar incluso las manuales").
 */
@Entity
@Table(name = "app_ui_translation", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ui_translation_text_lang",
                          columnNames = {"ui_text_id", "language_code"})
}, indexes = {
        @Index(name = "idx_ui_translation_lang", columnList = "language_code"),
        @Index(name = "idx_ui_translation_text", columnList = "ui_text_id")
})
@Getter @Setter
public class UiTranslation {

    public enum Source { AI, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK al UiText origen. */
    @Column(name = "ui_text_id", nullable = false)
    private Long uiTextId;

    /** Código del idioma destino (ej: "en", "pt-BR"). Coincide con Language.code. */
    @Column(name = "language_code", length = 10, nullable = false)
    private String languageCode;

    /** Texto traducido. */
    @Column(name = "translated_text", columnDefinition = "TEXT", nullable = false)
    private String translatedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private Source source = Source.AI;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (source == null) source = Source.AI;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
